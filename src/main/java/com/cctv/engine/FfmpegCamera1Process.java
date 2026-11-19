package com.cctv.engine;

import com.cctv.app.util.FfmpegBinary;
import com.cctv.contract.ClipFinalized;
import com.cctv.contract.EventBus;
import com.cctv.contract.Frame;
import com.cctv.contract.FrameSource;
import com.cctv.contract.SegmentStarted;
import com.cctv.contract.StreamState;
import com.cctv.contract.StreamStatusEvent;
import com.cctv.contract.Detection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-process Camera 1 owner: FFmpeg opens the main RTSP input once, packet-copies it into
 * MKV segments, and decodes a low-rate 640x360 BGR output for the AI consumer.
 *
 * <p>FFmpeg is resolved by {@link com.cctv.app.util.FfmpegBinary} (bundled copy first, then PATH).
 * The main RTSP URL may point straight at a camera (including user:password credentials) or at a
 * local relay. Credentials in the URL are visible to local process inspection, just as they are
 * already stored in the runtime database; owners who need to hide them should keep theirs in a
 * local relay such as MediaMTX.
 * The stdout reader continuously drains raw frames into a one-slot handoff, so slow inference
 * cannot back-pressure the recorder. Segment CSV entries are emitted only after a segment closes.
 */
public final class FfmpegCamera1Process implements FrameSource {
    private static final Logger log = LoggerFactory.getLogger(FfmpegCamera1Process.class);
    private static final int AI_WIDTH = 640;
    private static final int AI_HEIGHT = 360;
    private static final int FRAME_BYTES = AI_WIDTH * AI_HEIGHT * 3;
    private static final long STALE_AFTER_MS = 3_000;
    private static final long MIN_RECONNECT_MS = 1_000;
    private static final long MAX_RECONNECT_MS = 15_000;
    private static final Pattern SEGMENT_OPEN = Pattern.compile("Opening '(.+\\.mkv)' for writing");
    private static final Pattern PTS_TIME = Pattern.compile("pts_time:([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))");

    private final long cameraId;
    private final String rtspUrl;
    private final Path recordingsDir;
    private final int segmentSeconds;
    private final double aiRateFps;
    private final EventBus events;
    private final AtomicReference<Frame> latest = new AtomicReference<>();
    private final AtomicReference<Frame> latestForDisplay = new AtomicReference<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<Path, Long> openedSegments = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long> framePts = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> detectionTimes = new ConcurrentLinkedQueue<>();
    private final EventBus.Subscription detectionSubscription;
    private volatile Process process;
    private volatile Thread worker;
    private volatile long lastFrameAtMs;

    public FfmpegCamera1Process(long cameraId, String rtspUrl, Path recordingsDir,
                                int segmentSeconds, double aiRateFps, EventBus events) {
        if (cameraId <= 0) throw new IllegalArgumentException("cameraId must be positive");
        if (rtspUrl == null || !rtspUrl.regionMatches(true, 0, "rtsp://", 0, 7)) {
            throw new IllegalArgumentException("main stream URL must use RTSP");
        }
        URI sourceUri = URI.create(rtspUrl);
        String host = sourceUri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("main stream URL has no host");
        }
        this.cameraId = cameraId;
        this.rtspUrl = rtspUrl;
        this.recordingsDir = Objects.requireNonNull(recordingsDir, "recordingsDir")
                .toAbsolutePath().normalize();
        if (segmentSeconds < 10 || segmentSeconds > 30) {
            throw new IllegalArgumentException("segmentSeconds must be in [10,30]");
        }
        if (!(aiRateFps > 0.0) || aiRateFps > 60.0 || !Double.isFinite(aiRateFps)) {
            throw new IllegalArgumentException("aiRateFps must be in (0,60]");
        }
        this.segmentSeconds = segmentSeconds;
        this.aiRateFps = aiRateFps;
        this.events = Objects.requireNonNull(events, "events");
        this.detectionSubscription = events.subscribe(Detection.class, detection -> {
            if (detection.cameraId() == cameraId) markDetection(detection.frameUtcMs());
        });
    }

    /** Starts the FFmpeg owner/reconnect loop. Safe to call once. */
    public void start() {
        if (closed.get()) throw new IllegalStateException("capture process is closed");
        if (!started.compareAndSet(false, true)) return;
        publish(StreamState.CONNECTING, "Starting Camera 1 capture and recorder");
        worker = new Thread(this::runLoop, "camera-" + cameraId + "-ffmpeg");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public Optional<Frame> next() {
        Frame frame = latest.getAndSet(null);
        if (frame == null) return Optional.empty();
        if (System.currentTimeMillis() - frame.capturedUtcMs() > STALE_AFTER_MS) {
            return Optional.empty();
        }
        return Optional.of(frame);
    }

    /** Non-destructive latest-frame view for the JavaFX camera cell; does not consume the AI slot. */
    public Optional<Frame> latestFrame() {
        Frame frame = latestForDisplay.get();
        if (frame == null || System.currentTimeMillis() - frame.capturedUtcMs() > STALE_AFTER_MS) {
            return Optional.empty();
        }
        return Optional.of(frame);
    }

    public boolean isRunning() {
        Thread t = worker;
        return t != null && t.isAlive() && !closed.get();
    }

    private void runLoop() {
        long backoffMs = MIN_RECONNECT_MS;
        while (!closed.get()) {
            try {
                runOneSession();
                backoffMs = MIN_RECONNECT_MS;
            } catch (IOException startupFailure) {
                if (!closed.get()) {
                    log.error("camera {} cannot start FFmpeg or access the recording directory: {}",
                            cameraId, safeDetail(startupFailure));
                    publish(StreamState.ERROR, "FFmpeg unavailable or recording path inaccessible");
                }
                break;
            } catch (Exception failure) {
                if (!closed.get()) {
                    log.warn("camera {} FFmpeg session ended: {}", cameraId, safeDetail(failure));
                }
            }
            latest.set(null);
            latestForDisplay.set(null);
            if (!closed.get()) {
                publish(StreamState.RECONNECTING, "Camera 1 reconnecting");
                sleepWhileOpen(backoffMs);
                backoffMs = Math.min(MAX_RECONNECT_MS, backoffMs * 2);
            }
        }
        publish(StreamState.OFFLINE, "Camera 1 stopped");
    }

    private void runOneSession() throws Exception {
        Files.createDirectories(recordingsDir);
        framePts.clear();
        openedSegments.clear();
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        long sessionStartUtcMs = System.currentTimeMillis();
        Path csv = recordingsDir.resolve("camera-" + cameraId + "-" + sessionId + ".csv");
        String template = recordingsDir.resolve("camera-" + cameraId + "-" + sessionId + "-%05d.mkv")
                .toString();

        List<String> command = new ArrayList<>(List.of(
                FfmpegBinary.resolve(), "-hide_banner", "-loglevel", "info",
                "-rtsp_transport", "tcp", "-timeout", "5000000",
                "-i", rtspUrl,
                "-map", "0:v:0", "-an", "-c:v", "copy",
                "-f", "segment", "-segment_time", Integer.toString(segmentSeconds),
                "-reset_timestamps", "1", "-segment_format", "matroska",
                "-segment_list", csv.toString(), "-segment_list_type", "csv",
                "-segment_list_size", "0", "-segment_list_flags", "+live", template,
                "-map", "0:v:0", "-vf", String.format(Locale.ROOT,
                        "fps=%.3f,scale=640:360:force_original_aspect_ratio=decrease,"
                                + "pad=640:360:(ow-iw)/2:(oh-ih)/2,showinfo", aiRateFps),
                "-pix_fmt", "bgr24", "-f", "rawvideo", "pipe:1"
        ));

        Process child = new ProcessBuilder(command).start();
        process = child;
        if (closed.get()) requestStop(child);
        AtomicBoolean sessionEnded = new AtomicBoolean();
        AtomicBoolean online = new AtomicBoolean();
        Thread stderr = new Thread(() -> readStderr(child, sessionStartUtcMs),
                "camera-" + cameraId + "-ffmpeg-stderr");
        Thread frames = new Thread(() -> readFrames(child, sessionStartUtcMs, online),
                "camera-" + cameraId + "-ffmpeg-frames");
        Thread segments = new Thread(() -> monitorSegments(csv, sessionStartUtcMs, sessionEnded),
                "camera-" + cameraId + "-ffmpeg-segments");
        stderr.setDaemon(true);
        frames.setDaemon(true);
        segments.setDaemon(true);
        stderr.start();
        frames.start();
        segments.start();

        int exit = child.waitFor();
        sessionEnded.set(true);
        frames.join(2_000);
        stderr.join(2_000);
        segments.join(2_000);
        process = null;
        if (!closed.get() && exit != 0) {
            throw new IllegalStateException("FFmpeg exited with code " + exit);
        }
    }

    private void readFrames(Process child, long sessionStartUtcMs, AtomicBoolean online) {
        try (InputStream raw = child.getInputStream()) {
            byte[] bytes = new byte[FRAME_BYTES];
            while (true) {
                int offset = 0;
                while (offset < FRAME_BYTES) {
                    int count = raw.read(bytes, offset, FRAME_BYTES - offset);
                    if (count < 0) return;
                    offset += count;
                }
                if (closed.get()) continue; // drain stdout until FFmpeg exits cleanly on shutdown
                BufferedImage image = new BufferedImage(AI_WIDTH, AI_HEIGHT,
                        BufferedImage.TYPE_3BYTE_BGR);
                byte[] target = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
                System.arraycopy(bytes, 0, target, 0, FRAME_BYTES);
                long capturedUtcMs = System.currentTimeMillis();
                Long sourcePtsMs = framePts.poll();
                long ptsMs = sourcePtsMs != null ? sourcePtsMs
                        : Math.max(0L, capturedUtcMs - sessionStartUtcMs);
                Frame frame = new Frame(cameraId, ptsMs, capturedUtcMs, AI_WIDTH, AI_HEIGHT, image);
                latest.set(frame);
                latestForDisplay.set(frame);
                if (online.compareAndSet(false, true)) {
                    publish(StreamState.ONLINE, "Camera 1 online");
                }
            }
        } catch (Exception failure) {
            if (!closed.get()) log.warn("camera {} raw-frame pipe ended: {}", cameraId, failure.toString());
        }
    }

    private void readStderr(Process child, long sessionStartUtcMs) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(child.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher pts = PTS_TIME.matcher(line);
                if (pts.find()) {
                    try {
                        framePts.offer(Math.round(Double.parseDouble(pts.group(1)) * 1_000.0));
                    } catch (NumberFormatException ignored) { }
                }
                Matcher opened = SEGMENT_OPEN.matcher(line);
                if (opened.find()) {
                    Path file = Path.of(opened.group(1)).toAbsolutePath().normalize();
                    long startUtcMs = System.currentTimeMillis();
                    openedSegments.put(file, startUtcMs);
                    publish(new SegmentStarted(cameraId, startUtcMs, file));
                }
                if (line.toLowerCase(Locale.ROOT).contains("error")) {
                    log.warn("camera {} FFmpeg: {}", cameraId, redact(line));
                }
            }
        } catch (Exception failure) {
            if (!closed.get()) log.debug("camera {} FFmpeg stderr reader ended: {}", cameraId, failure.toString());
        }
    }

    private void monitorSegments(Path csv, long sessionStartUtcMs, AtomicBoolean sessionEnded) {
        int processed = 0;
        while (!sessionEnded.get()) {
            processed = processCsvRows(csv, sessionStartUtcMs, processed);
            sleep(200);
        }
        processCsvRows(csv, sessionStartUtcMs, processed);
    }

    private int processCsvRows(Path csv, long sessionStartUtcMs, int processed) {
        if (!Files.isRegularFile(csv)) return processed;
        try {
            List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
            for (int i = processed; i < lines.size(); i++) {
                String[] columns = lines.get(i).split(",", 3);
                if (columns.length != 3) continue;
                Path listedPath = Path.of(columns[0]);
                Path file = (listedPath.isAbsolute() ? listedPath : recordingsDir.resolve(listedPath))
                        .toAbsolutePath().normalize();
                double startSeconds = Double.parseDouble(columns[1]);
                double endSeconds = Double.parseDouble(columns[2]);
                long startUtcMs = openedSegments.computeIfAbsent(file,
                        ignored -> sessionStartUtcMs + Math.round(startSeconds * 1_000.0));
                long durationMs = Math.max(0L, Math.round((endSeconds - startSeconds) * 1_000.0));
                long endUtcMs = startUtcMs + durationMs;
                long size = Files.exists(file) ? Files.size(file) : 0L;
                boolean keep = hasDetection(startUtcMs, endUtcMs);
                publish(new ClipFinalized(cameraId, startUtcMs, endUtcMs, size, file, keep));
                openedSegments.remove(file);
            }
            return lines.size();
        } catch (Exception failure) {
            log.warn("camera {} segment list parse failed: {}", cameraId, failure.toString());
            return processed;
        }
    }

    private void markDetection(long frameUtcMs) {
        detectionTimes.offer(frameUtcMs);
        long cutoff = System.currentTimeMillis() - 3_600_000L;
        while (true) {
            Long first = detectionTimes.peek();
            if (first == null || first >= cutoff) break;
            detectionTimes.poll();
        }
    }

    private boolean hasDetection(long startUtcMs, long endUtcMs) {
        for (Long timestamp : detectionTimes) {
            if (timestamp >= startUtcMs && timestamp <= endUtcMs) return true;
        }
        return false;
    }

    private void publish(StreamState state, String detail) {
        publish(StreamStatusEvent.now(cameraId, state, detail));
    }

    private void publish(Object event) {
        try {
            events.publish(event);
        } catch (RuntimeException failure) {
            log.warn("camera {} event listener failed: {}", cameraId, failure.toString());
        }
    }

    private String redact(String value) {
        return value.replaceAll("(?i)rtsp://[^\\s'\"]+", "rtsp://[redacted]");
    }

    private String safeDetail(Exception failure) {
        String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName()
                : redact(failure.getClass().getSimpleName() + ": " + message);
    }

    private void sleepWhileOpen(long millis) {
        long remaining = millis;
        while (!closed.get() && remaining > 0) {
            long slice = Math.min(100L, remaining);
            long before = System.nanoTime();
            sleep(slice);
            long elapsed = Math.max(1L, (System.nanoTime() - before) / 1_000_000L);
            remaining -= elapsed;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void requestStop(Process child) {
        if (child == null || !child.isAlive()) return;
        try {
            child.getOutputStream().write("q\n".getBytes(StandardCharsets.US_ASCII));
            child.getOutputStream().flush();
            if (!child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                child.destroyForcibly();
                child.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Exception failure) {
            child.destroyForcibly();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        detectionSubscription.close();
        latest.set(null);
        latestForDisplay.set(null);
        requestStop(process);
        Thread t = worker;
        if (t != null) {
            try {
                t.join(8_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
