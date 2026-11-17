package com.cctv.spike;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spike 0a: one JavaCV connection (RTSP or file) feeding a JavaFX ImageView.
 *
 * <p>Exit criteria: frames appear in a window, the grab thread reports a steady decode rate,
 * and the same grabber keeps the connection alive for several minutes. Nothing else — no
 * recording, no AI, no wall grid.
 *
 * <p>Run: {@code java -cp "target/classes;$(cat cp.txt)" com.cctv.spike.JavaCvSpike <url|file>}
 */
public final class JavaCvSpikeApp extends Application {

    private final AtomicReference<BufferedImage> latest = new AtomicReference<>();
    private volatile boolean running = true;

    @Override
    public void start(Stage stage) {
        String source = getParameters().getRaw().isEmpty()
                ? "testdata/test_walkthrough.mp4"
                : getParameters().getRaw().get(0);

        ImageView view = new ImageView();
        view.setPreserveRatio(true);
        view.setSmooth(true);
        StackPane root = new StackPane(view);
        root.setStyle("-fx-background-color: black;");
        Scene scene = new Scene(root, 1024, 600);
        view.fitWidthProperty().bind(scene.widthProperty());
        view.fitHeightProperty().bind(scene.heightProperty());
        stage.setTitle("0a JavaCV -> ImageView  [" + source + "]");
        stage.setScene(scene);
        stage.show();

        Thread grabber = new Thread(() -> grabLoop(source), "javaCV-grab");
        grabber.setDaemon(true);
        grabber.start();

        new javafx.animation.AnimationTimer() {
            @Override public void handle(long now) {
                BufferedImage img = latest.getAndSet(null);
                if (img != null) {
                    view.setImage(toFx(img));
                }
            }
        }.start();

        stage.setOnCloseRequest(e -> running = false);
    }

    private void grabLoop(String source) {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(source);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.setOption("rtsp_transport", "tcp");
            grabber.setOption("fflags", "nobuffer");
            grabber.setOption("flags", "low_delay");
            long t0 = System.nanoTime();
            grabber.start();
            System.out.printf("[0a] started %s %dx%d @%.2f fps codec=%d%n",
                    source, grabber.getImageWidth(), grabber.getImageHeight(),
                    grabber.getFrameRate(), grabber.getVideoCodec());

            long frames = 0, lastFrames = 0, lastReport = System.currentTimeMillis();
            long maxGapMs = 0, prevPts = -1;
            while (running) {
                Frame f = grabber.grabImage();
                if (f == null) {
                    long gap = System.currentTimeMillis() - prevPts;
                    maxGapMs = Math.max(maxGapMs, prevPts < 0 ? 0 : gap);
                    break;
                }
                prevPts = System.currentTimeMillis();
                BufferedImage img = converter.convert(f);
                if (img != null) {
                    latest.set(img);
                    frames++;
                }
                long now = System.currentTimeMillis();
                if (now - lastReport >= 5000) {
                    double fps = (frames - lastFrames) * 1000.0 / (now - lastReport);
                    System.out.printf("[0a] frames=%d decodeFps=%.1f maxInterFrameGapMs=%d%n",
                            frames, fps, maxGapMs);
                    lastFrames = frames;
                    lastReport = now;
                    if (maxGapMs > 3000) {
                        maxGapMs = 0;
                    }
                }
            }
            System.out.printf("[0a] done frames=%d in %.1fs%n", frames,
                    (System.nanoTime() - t0) / 1e9);
        } catch (Exception e) {
            System.err.println("[0a] FAILED: " + e);
            throw new RuntimeException(e);
        } finally {
            Platform.runLater(() -> { if (!Platform.isFxApplicationThread()) {} });
        }
    }

    private static Image toFx(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        javafx.scene.image.WritableImage fx = new javafx.scene.image.WritableImage(w, h);
        int[] buf = new int[w * h];
        img.getRGB(0, 0, w, h, buf, 0, w);
        fx.getPixelWriter().setPixels(0, 0, w, h,
                javafx.scene.image.PixelFormat.getIntArgbInstance(), buf, 0, w);
        return fx;
    }
}
