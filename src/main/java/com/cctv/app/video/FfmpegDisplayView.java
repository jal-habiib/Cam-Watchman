package com.cctv.app.video;

import com.cctv.contract.StreamState;
import com.cctv.contract.StreamStatusEvent;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javafx.animation.AnimationTimer;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FFmpeg/JavaCV display-only RTSP surface for cameras without the full AI/record pipeline.
 * Decoding happens on a dedicated daemon worker; only the newest completed image is copied
 * into JavaFX on the application thread. RTSP is forced over TCP and retries use bounded backoff.
 */
public final class FfmpegDisplayView extends Pane implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FfmpegDisplayView.class);
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

    private volatile FFmpegFrameGrabber activeGrabber;
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
            FFmpegFrameGrabber grabber = null;
            Java2DFrameConverter converter = new Java2DFrameConverter();
            try {
                grabber = new FFmpegFrameGrabber(url);
                grabber.setOption("rtsp_transport", "tcp");
                grabber.setOption("rw_timeout", "5000000");
                grabber.setOption("threads", "1");
                activeGrabber = grabber;
                grabber.start();
                log.info("camera {} FFmpeg display decoder connected ({}x{}, RTSP/TCP, {})",
                        cameraId, grabber.getImageWidth(), grabber.getImageHeight(),
                        com.cctv.app.config.Redaction.redact(url));
                retryMs = MIN_RETRY_MS;

                while (!closed) {
                    org.bytedeco.javacv.Frame frame = grabber.grabImage();
                    if (frame == null) {
                        continue;
                    }
                    BufferedImage converted = converter.convert(frame);
                    if (converted == null) {
                        continue;
                    }
                    latest.set(converted);
                    lastFrameNanos = System.nanoTime();
                    receivedFrames.incrementAndGet();
                    if (!online) {
                        online = true;
                        publish(StreamState.ONLINE, "Receiving decoded video frames");
                        log.info("camera {} FFmpeg display received first frame: {}x{}",
                                cameraId, converted.getWidth(), converted.getHeight());
                    }
                }
            } catch (Exception failure) {
                if (!closed) {
                    online = false;
                    publish(StreamState.RECONNECTING, "Display stream reconnecting");
                    log.warn("camera {} FFmpeg display session failed ({}): {}",
                            cameraId, com.cctv.app.config.Redaction.redact(url),
                            safeMessage(failure));
                }
            } finally {
                activeGrabber = null;
                if (grabber != null) {
                    try {
                        grabber.stop();
                    } catch (Exception stopFailure) {
                        log.debug("camera {} display grabber stop: {}", cameraId,
                                safeMessage(stopFailure));
                    }
                    try {
                        grabber.release();
                    } catch (Exception releaseFailure) {
                        log.debug("camera {} display grabber release: {}", cameraId,
                                safeMessage(releaseFailure));
                    }
                }
                converter.close();
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
