package com.cctv.app.video;

import com.cctv.contract.Frame;
import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.function.Supplier;
import javafx.animation.AnimationTimer;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Camera 1 display path fed by the engine's non-destructive display tap
 * ({@link Camera1Pipeline#latestFrameAfter(long)}).
 *
 * <p>The image is copied on the JavaFX thread into a {@link WritableImage}; the engine keeps its own
 * copy, so the AI worker's frame is never mutated. The timer only converts when the frame's capture
 * timestamp is newer than the last one shown, which keeps the copy work proportional to camera fps.
 */
public final class EngineVideoView extends Pane implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EngineVideoView.class);
    private static final long FRAME_TIMEOUT_MS = 3_000;

    private final Supplier<Optional<Frame>> frameSupplier;
    private final ImageView view = new ImageView();
    private WritableImage image;
    private int[] pixels = new int[0];
    private volatile Frame lastFrame;
    private long lastCapturedUtcMs;
    private long lastFrameAtMs;
    private long shownFrames;
    private AnimationTimer timer;
    private boolean started;
    private boolean closed;

    public EngineVideoView(Supplier<Optional<Frame>> frameSupplier) {
        this.frameSupplier = frameSupplier;
        setStyle("-fx-background-color:#050505;");
        setMinSize(0, 0);
        view.setPreserveRatio(false);
        view.setSmooth(true);
        view.setManaged(false);
        getChildren().add(view);
    }

    public void start() {
        if (started || closed) {
            return;
        }
        started = true;
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                paint();
            }
        };
        timer.start();
    }

    private void paint() {
        Optional<Frame> next = frameSupplier.get();
        if (next.isEmpty() || next.get().capturedUtcMs() <= lastCapturedUtcMs) {
            return;
        }
        Frame frame = next.get();
        lastCapturedUtcMs = frame.capturedUtcMs();
        lastFrameAtMs = System.currentTimeMillis();
        lastFrame = frame;
        BufferedImage source = frame.image();
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
        shownFrames++;
        layout();
    }

    /** True when the display path has not seen a new frame for a while (frozen stream). */
    public boolean isStale() {
        return started && lastFrameAtMs > 0 && System.currentTimeMillis() - lastFrameAtMs > FRAME_TIMEOUT_MS;
    }

    /** Newest displayed frame, used as the intrusion alert snapshot (plan 5d). */
    public Optional<BufferedImage> snapshot() {
        Frame frame = lastFrame;
        return frame == null ? Optional.empty() : Optional.of(frame.image());
    }

    public long shownFrames() {
        return shownFrames;
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
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        getChildren().clear();
        log.info("camera 1 display link closed ({} frame(s) shown)", shownFrames);
    }
}
