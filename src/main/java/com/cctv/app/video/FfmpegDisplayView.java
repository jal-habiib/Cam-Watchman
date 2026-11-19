package com.cctv.app.video;

import com.cctv.app.util.FfmpegBinary;
import com.cctv.contract.StreamState;
import com.cctv.contract.StreamStatusEvent;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javafx.animation.AnimationTimer;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FFmpeg display-only RTSP surface for cameras without the full AI/record pipeline.
 * A dedicated FFmpeg child process decodes the RTSP stream to raw BGR24 frames on stdout;
 * only the newest completed image is copied into JavaFX on the application thread.
 * RTSP is forced over TCP and retries use bounded backoff.
 *
 * <p>This deliberately spawns the external {@code ffmpeg} executable (exactly as the Camera 1
 * pipeline does in {@code com.cctv.engine.FfmpegCamera1Process}) instead of JavaCV's
 * {@code FFmpegFrameGrabber}: the bundled JavaCV FFmpeg build cannot open MediaMTX's RTSP relays
 * ({@code avformat_open_input() error -875574520}, then hangs), while the external ffmpeg binary
 * (see {@link com.cctv.app.util.FfmpegBinary}) opens the same streams immediately.
 */
public final class FfmpegDisplayView extends Pane implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FfmpegDisplayView.class);
    private static final int DISPLAY_WIDTH = 640;
    private static final int DISPLAY_HEIGHT = 360;
    private static final int FRAME_BYTES = DISPLAY_WIDTH * DISPLAY_HEIGHT * 3;
    private static final long FRAME_STALE_NANOS = 10_000_000_000L;
    private static final long MIN_RETRY_MS = 1_000L;
    private static final long MAX_RETRY_MS = 15_000L;

    private final long cameraId;
    private final String url;
    private final Consumer<StreamStatusEvent> publisher;
    private final ImageView view = new ImageView();
    private final AtomicReference<BufferedImage> latest = new AtomicReference<>();
    private final AtomicLong receivedFrames = new AtomicLong();
    private final AnimationTimer painter;

    private volatile Process process;
    private volatile Thread worker;
    private volatile boolean closed;
    private volatile boolean started;
    private volatile boolean online;
    private volatile long lastFrameNanos;
    private long paintedFrames;
    private long paintedSequence;
    private WritableImage image;
    private int[] pixels = new int[0];

    public FfmpegDisplayView(long cameraId, java.net.URI uri,
                             Consumer<StreamStatusEvent> publisher) {
        this.cameraId = cameraId;
        this.url = uri.toString();
        this.publisher = publisher;
        setMinSize(0, 0);
        setStyle("-fx-background-color:#0b0b0b;");
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.setManaged(false);
        getChildren().add(view);
        painter = new AnimationTimer() {
            @Override
            public void handle(long now) {
                paint(now);
            }
        };
    }

    public synchronized void start() {
        if (started || closed) {
            return;
        }
        started = true;
        publish(StreamState.CONNECTING, "Connecting to RTSP stream");
        worker = new Thread(this::decodeLoop, "camera-" + cameraId + "-display-ffmpeg");
        worker.setDaemon(true);
        worker.start();
        painter.start();
    }

    private void decodeLoop() {
        long retryMs = MIN_RETRY_MS;
        while (!closed) {
            try {
                runOneSession();
                retryMs = MIN_RETRY_MS;
            } catch (Exception failure) {
                if (!closed) {
                    online = false;
                    publish(StreamState.RECONNECTING, "Display stream reconnecting");
                    log.warn("camera {} FFmpeg display session failed ({}): {}",
                            cameraId, com.cctv.app.config.Redaction.redact(url),
                            safeMessage(failure));
                }
            }
            if (!closed) {
                sleep(retryMs);
                retryMs = Math.min(MAX_RETRY_MS, retryMs * 2);
            }
        }
        online = false;
        publish(StreamState.OFFLINE, "Display stream stopped");
        log.info("camera {} FFmpeg display worker stopped after {} frame(s)",
                cameraId, receivedFrames.get());
    }

    private void runOneSession() throws Exception {
        List<String> command = List.of(
                FfmpegBinary.resolve(), "-hide_banner", "-nostats", "-loglevel", "warning",
                "-rtsp_transport", "tcp", "-timeout", "5000000",
                "-i", url,
                "-map", "0:v:0", "-an",
                "-vf", "scale=640:360:force_original_aspect_ratio=decrease,"
                        + "pad=640:360:(ow-iw)/2:(oh-ih)/2",
                "-pix_fmt", "bgr24", "-f", "rawvideo", "pipe:1"
        );

        Process child = new ProcessBuilder(command).start();
        process = child;
        if (closed) {
            requestStop(child);
            return;
        }

        Thread stderrDrain = new Thread(() -> drainStderr(child),
                "camera-" + cameraId + "-display-ffmpeg-stderr");
        stderrDrain.setDaemon(true);
        stderrDrain.start();

        readFrames(child);

        int exit = child.waitFor();
        process = null;
        if (!closed && exit != 0) {
            throw new IllegalStateException("FFmpeg exited with code " + exit);
        }
    }

    private void readFrames(Process child) throws Exception {
        try (InputStream raw = child.getInputStream()) {
            byte[] bytes = new byte[FRAME_BYTES];
            while (!closed) {
                int offset = 0;
                while (offset < FRAME_BYTES) {
                    int count = raw.read(bytes, offset, FRAME_BYTES - offset);
                    if (count < 0) {
                        return;
                    }
                    offset += count;
                }
                BufferedImage converted = new BufferedImage(DISPLAY_WIDTH, DISPLAY_HEIGHT,
                        BufferedImage.TYPE_3BYTE_BGR);
                byte[] target = ((DataBufferByte) converted.getRaster().getDataBuffer()).getData();
                System.arraycopy(bytes, 0, target, 0, FRAME_BYTES);
                latest.set(converted);
                lastFrameNanos = System.nanoTime();
                receivedFrames.incrementAndGet();
                if (!online) {
                    online = true;
                    publish(StreamState.ONLINE, "Receiving decoded video frames");
                    log.info("camera {} FFmpeg display received first frame: {}x{}",
                            cameraId, DISPLAY_WIDTH, DISPLAY_HEIGHT);
                }
            }
        }
    }

    private void drainStderr(Process child) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(child.getErrorStream(), StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) {
                // drained to keep the pipe from filling; already quieted with -loglevel warning
            }
        } catch (Exception ignored) {
            // process teardown
        }
    }

    private void paint(long now) {
        if (online && lastFrameNanos > 0 && now - lastFrameNanos > FRAME_STALE_NANOS) {
            online = false;
            publish(StreamState.OFFLINE, "Display stalled (no frames for 10 s)");
        }
        BufferedImage source = latest.get();
        long sequence = receivedFrames.get();
        if (source == null || sequence == paintedSequence) {
            return;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        if (image == null || (int) image.getWidth() != width || (int) image.getHeight() != height) {
            image = new WritableImage(width, height);
            pixels = new int[width * height];
            view.setImage(image);
        }
        source.getRGB(0, 0, width, height, pixels, 0, width);
        image.getPixelWriter().setPixels(0, 0, width, height,
                PixelFormat.getIntArgbInstance(), pixels, 0, width);
        paintedSequence = sequence;
        paintedFrames++;
        layout();
    }

    public Optional<BufferedImage> snapshotImage() {
        BufferedImage source = latest.get();
        if (source == null) {
            return Optional.empty();
        }
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return Optional.of(copy);
    }

    public long receivedFrames() {
        return receivedFrames.get();
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();
        view.setFitWidth(width);
        view.setFitHeight(height);
        view.resizeRelocate(0, 0, width, height);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        painter.stop();
        requestStop(process);
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
        getChildren().clear();
        log.info("camera {} FFmpeg display closed ({} frame(s) painted)",
                cameraId, paintedFrames);
    }

    private void publish(StreamState state, String detail) {
        if (publisher != null) {
            publisher.accept(StreamStatusEvent.now(cameraId, state, detail));
        }
    }

    private void requestStop(Process child) {
        if (child == null || !child.isAlive()) {
            return;
        }
        try {
            child.getOutputStream().write("q\n".getBytes(StandardCharsets.US_ASCII));
            child.getOutputStream().flush();
            if (!child.waitFor(5, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                child.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (Exception failure) {
            child.destroyForcibly();
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        String safe = com.cctv.app.config.Redaction.redact(message);
        return safe.length() > 240 ? safe.substring(0, 240) : safe;
    }
}
