package com.cctv.engine;

import com.cctv.contract.EventBus;
import com.cctv.contract.Frame;
import com.cctv.contract.FrameSource;
import com.cctv.contract.StreamState;
import com.cctv.contract.StreamStatusEvent;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Camera 1 capture owner. Exactly one FFmpegFrameGrabber is created per active session.
 * The capture thread publishes only the newest 640x360 image through a one-slot handoff.
 * Consumers never touch the grabber and cannot stall the capture loop.
 */
public final class Camera1StreamManager implements FrameSource {
    public static final int AI_WIDTH = 640;
    public static final int AI_HEIGHT = 360;
    private static final long STALE_AFTER_MS = 3_000;
    private static final long MIN_RECONNECT_MS = 1_000;
    private static final long MAX_RECONNECT_MS = 15_000;

    private final long cameraId;
    private final URI mainUrl;
    private final EventBus events;
    private final AtomicReference<Frame> latest = new AtomicReference<>();
    private final AtomicReference<Frame> latestForDisplay = new AtomicReference<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Thread worker;

    public Camera1StreamManager(long cameraId, URI mainUrl, EventBus events) {
        if (cameraId <= 0) throw new IllegalArgumentException("cameraId must be positive");
        this.cameraId = cameraId;
        this.mainUrl = Objects.requireNonNull(mainUrl, "mainUrl");
        if (!"rtsp".equalsIgnoreCase(mainUrl.getScheme())) {
            throw new IllegalArgumentException("Camera 1 main URL must use RTSP");
        }
        this.events = Objects.requireNonNull(events, "events");
    }

    /** Starts the capture/reconnect worker. Safe to call once. */
    public void start() {
        if (closed.get()) throw new IllegalStateException("stream manager is closed");
        if (!started.compareAndSet(false, true)) return;
        publish(StreamState.CONNECTING, "Connecting to Camera 1");
        worker = new Thread(this::runLoop, "camera-1-capture");
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

    /**
     * Non-destructive display tap. Returns a pixel-isolated copy so JavaFX rendering cannot
     * mutate the image currently being read by the AI worker. Call only when the frame timestamp
     * changes (typically 10-15 Hz) to avoid unnecessary image copies.
     */
    public Optional<Frame> latestFrame() {
        return latestFrameAfter(0L);
    }

    /** Returns a deep copy only when a newer captured frame is available. */
    public Optional<Frame> latestFrameAfter(long lastCapturedUtcMs) {
        Frame frame = latestForDisplay.get();
        if (frame == null || frame.capturedUtcMs() <= lastCapturedUtcMs
                || System.currentTimeMillis() - frame.capturedUtcMs() > STALE_AFTER_MS) {
            return Optional.empty();
        }
        BufferedImage copy = copyImage(frame.image());
        return Optional.of(new Frame(frame.cameraId(), frame.ptsMs(), frame.capturedUtcMs(),
                frame.width(), frame.height(), copy));
    }

    private static BufferedImage copyImage(BufferedImage source) {
        WritableRaster raster = source.copyData(null);
        return new BufferedImage(source.getColorModel(), raster,
                source.isAlphaPremultiplied(), null);
    }

    public boolean isRunning() {
        Thread t = worker;
        return t != null && t.isAlive() && !closed.get();
    }

    private void runLoop() {
        long backoffMs = MIN_RECONNECT_MS;
        while (!closed.get()) {
            boolean connected = false;
            try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(mainUrl.toString());
                 Java2DFrameConverter converter = new Java2DFrameConverter()) {
                configure(grabber);
                grabber.start();
                latest.set(null);
                latestForDisplay.set(null);
                while (!closed.get()) {
                    org.bytedeco.javacv.Frame decoded = grabber.grabImage();
                    if (decoded == null) throw new IllegalStateException("Camera stream ended");
                    BufferedImage image = converter.convert(decoded);
                    if (image == null) continue;
                    long now = System.currentTimeMillis();
                    long ptsMs = decoded.timestamp > 0 ? decoded.timestamp / 1_000L : now;
                    Frame frame = new Frame(cameraId, ptsMs, now, AI_WIDTH, AI_HEIGHT,
                            scale(image, AI_WIDTH, AI_HEIGHT));
                    latest.set(frame);
                    latestForDisplay.set(frame);
                    if (!connected) {
                        connected = true;
                        backoffMs = MIN_RECONNECT_MS;
                        publish(StreamState.ONLINE, "Camera 1 online");
                    }
                }
            } catch (Exception failure) {
                latest.set(null);
                latestForDisplay.set(null);
                if (!closed.get()) {
                    publish(StreamState.RECONNECTING, safeDetail(failure));
                    sleep(backoffMs);
                    backoffMs = Math.min(MAX_RECONNECT_MS, backoffMs * 2);
                }
            }
        }
        publish(StreamState.OFFLINE, "Camera 1 stopped");
    }

    private static void configure(FFmpegFrameGrabber grabber) {
        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("fflags", "nobuffer");
        // FFmpeg 8's RTSP demuxer uses "timeout" (microseconds) for TCP socket I/O.
        grabber.setOption("timeout", "5000000");
        grabber.setOption("reorder_queue_size", "0");
    }

    private void publish(StreamState state, String detail) {
        try {
            events.publish(StreamStatusEvent.now(cameraId, state, detail));
        } catch (RuntimeException ignored) {
            // A faulty observer must not terminate the native capture/reconnect worker.
        }
    }

    private static String safeDetail(Exception failure) {
        // Never include the RTSP URL: it may contain camera credentials.
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        return failure.getClass().getSimpleName() + ": "
                + message.replaceAll("(?i)rtsp://[^\\s]+", "rtsp://[redacted]");
    }

    private static BufferedImage scale(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        latest.set(null);
        latestForDisplay.set(null);
        Thread t = worker;
        if (t != null) {
            t.interrupt();
            try {
                t.join(6_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
