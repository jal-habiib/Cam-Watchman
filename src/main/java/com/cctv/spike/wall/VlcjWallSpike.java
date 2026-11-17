package com.cctv.spike.wall;

import com.sun.management.OperatingSystemMXBean;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat;

/**
 * Phase 0d spike: four VLCJ callback surfaces decoding four RTSP substreams at the same time,
 * with no AWT window and no JavaCV involvement (plan p.5, item 4).
 *
 * <p>Exit criterion (charter): a measured CPU and whole-process RAM number for a four-camera
 * wall, produced before the wall is built. This spike therefore:
 * <ul>
 *   <li>decodes {@code cam1_sub..cam4_sub} (640x360) through
 *       {@link CallbackVideoSurface} with an RV32 buffer, converting to ARGB for JavaFX;</li>
 *   <li>paints them into a 2x2 JavaFX grid so the frames are genuinely on screen;</li>
 *   <li>reports per-surface FPS and process CPU every second, and writes a JSON summary plus a
 *       PNG of the wall as evidence.</li>
 * </ul>
 *
 * <p>System properties: {@code spike.urls} (comma separated, default the four rig substreams),
 * {@code spike.seconds} (run length, default 60), {@code spike.out} (evidence directory).
 */
public final class VlcjWallSpike extends Application {

    private static final String DEFAULT_URLS = String.join(",",
            "rtsp://127.0.0.1:8554/cam1_sub",
            "rtsp://127.0.0.1:8554/cam2_sub",
            "rtsp://127.0.0.1:8554/cam3_sub",
            "rtsp://127.0.0.1:8554/cam4_sub");

    private final List<Surface> surfaces = new ArrayList<>();
    private final List<String> samples = new ArrayList<>();
    private final List<double[]> cpuTrace = new ArrayList<>();

    private Stage stage;
    private MediaPlayerFactory factory;
    private Path outDir;
    private long startedNanos;
    private int runSeconds;
    private double peakHeapMb;
    private boolean nativeFound;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        this.startedNanos = System.nanoTime();
        this.runSeconds = Integer.getInteger("spike.seconds", 60);
        this.outDir = Path.of(System.getProperty("spike.out", "evidence"));
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create evidence directory " + outDir, e);
        }

        this.nativeFound = new NativeDiscovery().discover();
        if (!nativeFound) {
            // Fall back to the installed VLC; NativeDiscovery should find it via the registry,
            // but the spike must not die silently if that fails.
            System.setProperty("jna.library.path", "C:\\Program Files\\VideoLAN\\VLC");
            System.err.println("[0d] NativeDiscovery failed, falling back to jna.library.path");
        }

        this.factory = new MediaPlayerFactory("--no-audio", "--no-video-title-show", "--quiet", "--no-osd");

        GridPane grid = new GridPane();
        grid.setStyle("-fx-background-color: #0b0b0b;");
        for (int i = 0; i < 2; i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setPercentWidth(50);
            c.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(c);
            RowConstraints r = new RowConstraints();
            r.setPercentHeight(50);
            r.setVgrow(Priority.ALWAYS);
            grid.getRowConstraints().add(r);
        }

        String[] urls = System.getProperty("spike.urls", DEFAULT_URLS).split(",");
        for (int i = 0; i < urls.length; i++) {
            Surface surface = new Surface(i, urls[i].trim());
            surfaces.add(surface);
            grid.add(surface.node, i % 2, i / 2);
            surface.open();
        }

        Scene scene = new Scene(grid, 1024, 620);
        stage.setTitle("0d spike — VLCJ x" + surfaces.size() + " callback surfaces");
        stage.setScene(scene);
        stage.show();

        AnimationTimer painter = new AnimationTimer() {
            @Override
            public void handle(long now) {
                for (Surface s : surfaces) {
                    s.paint();
                }
            }
        };
        painter.start();

        Thread sampler = new Thread(this::sampleLoop, "spike-sampler");
        sampler.setDaemon(true);
        sampler.start();

        PauseTransition stop = new PauseTransition(Duration.seconds(runSeconds));
        stop.setOnFinished(e -> finish(painter));
        stop.play();
    }

    private void sampleLoop() {
        long last = System.nanoTime();
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            double deltaSeconds = (now - last) / 1_000_000_000d;
            last = now;
            double cpu = os.getProcessCpuLoad();
            long heapUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            double heapMb = heapUsed / (1024d * 1024d);
            peakHeapMb = Math.max(peakHeapMb, heapMb);
            double t = (now - startedNanos) / 1_000_000_000d;
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%.0f,%.1f,%.1f", t, cpu * 100, heapMb));
            List<String> trace = new ArrayList<>();
            for (Surface s : surfaces) {
                double fps = s.fpsNow(deltaSeconds);
                line.append(',').append(String.format(Locale.ROOT, "%.1f", fps));
                trace.add(String.format(Locale.ROOT, "%.1f", fps));
            }
            samples.add(line.toString());
            cpuTrace.add(new double[] {t, cpu * 100});
            System.out.println("[0d] " + line);
        }
    }

    private void finish(AnimationTimer painter) {
        painter.stop();
        try {
            snapshot();
            writeSummary();
        } catch (Exception e) {
            System.err.println("[0d] evidence write failed: " + e);
            e.printStackTrace();
        }
        for (Surface s : surfaces) {
            s.close();
        }
        if (factory != null) {
            factory.release();
        }
        Platform.exit();
        System.exit(0);
    }

    /** Snapshot the whole scene to a PNG: proof that four surfaces really rendered. */
    private void snapshot() throws IOException {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        for (Surface s : surfaces) {
            javafx.geometry.Bounds b = s.node.localToScene(s.node.getBoundsInLocal());
            System.out.println(String.format(Locale.ROOT,
                    "[0d] layout cam%d x=%.0f y=%.0f w=%.0f h=%.0f image=%s fit=%.0fx%.0f",
                    s.index + 1, b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight(),
                    s.view.getImage() == null ? "null" : (int) s.view.getImage().getWidth() + "x" + (int) s.view.getImage().getHeight(),
                    s.view.getFitWidth(), s.view.getFitHeight()));
        }
        WritableImage image = stage.getScene().snapshot(null);
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        int[] argb = new int[w * h];
        image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        bi.setRGB(0, 0, w, h, argb, 0, w);
        Path file = outDir.resolve("0d-wall.png");
        ImageIO.write(bi, "png", file.toFile());
        System.out.println("[0d] wall snapshot -> " + file.toAbsolutePath());
    }

    private void writeSummary() throws IOException {
        double elapsed = (System.nanoTime() - startedNanos) / 1_000_000_000d;
        double cpuSum = 0;
        double cpuMax = 0;
        int cpuCount = 0;
        // Skip the first two seconds: JIT warm-up and first-frame allocation distort the sample.
        for (double[] s : cpuTrace) {
            if (s[0] < 2) {
                continue;
            }
            cpuSum += s[1];
            cpuMax = Math.max(cpuMax, s[1]);
            cpuCount++;
        }
        double cpuAvg = cpuCount == 0 ? -1 : cpuSum / cpuCount;

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"spike\": \"0d-vlcj-4-surfaces\",\n");
        json.append("  \"utc\": \"").append(Instant.now()).append("\",\n");
        json.append("  \"run_seconds\": ").append(runSeconds).append(",\n");
        json.append("  \"elapsed_seconds\": ").append(String.format(Locale.ROOT, "%.2f", elapsed)).append(",\n");
        json.append("  \"java\": \"").append(System.getProperty("java.version")).append("\",\n");
        json.append("  \"vm\": \"").append(System.getProperty("java.vm.name")).append(" ").append(System.getProperty("java.vm.version")).append("\",\n");
        json.append("  \"vlcj\": \"").append(versionOf(MediaPlayerFactory.class)).append("\",\n");
        json.append("  \"vlc_native_found\": ").append(nativeFound).append(",\n");
        json.append("  \"vlc_dir\": \"").append(escape(String.valueOf(System.getProperty("jna.library.path")))).append("\",\n");
        json.append("  \"cpu_cores\": ").append(Runtime.getRuntime().availableProcessors()).append(",\n");
        json.append("  \"process_cpu_pct_avg\": ").append(String.format(Locale.ROOT, "%.1f", cpuAvg)).append(",\n");
        json.append("  \"process_cpu_pct_max\": ").append(String.format(Locale.ROOT, "%.1f", cpuMax)).append(",\n");
        json.append("  \"heap_mb_peak\": ").append(String.format(Locale.ROOT, "%.1f", peakHeapMb)).append(",\n");
        json.append("  \"surfaces\": [\n");
        for (int i = 0; i < surfaces.size(); i++) {
            Surface s = surfaces.get(i);
            json.append("    {\"index\": ").append(i)
                    .append(", \"url\": \"").append(escape(s.url)).append('"')
                    .append(", \"frames\": ").append(s.frames.get())
                    .append(", \"first_frame_ms\": ").append(s.firstFrameMillis())
                    .append(", \"fps_avg\": ").append(String.format(Locale.ROOT, "%.1f", s.steadyFps))
                    .append(", \"fps_media\": ").append(String.format(Locale.ROOT, "%.1f", s.mediaFps))
                    .append(", \"dup_frames\": ").append(s.dupFrames)
                    .append(", \"media_ms\": ").append(s.mediaMs)
                    .append(", \"width\": ").append(s.width)
                    .append(", \"height\": ").append(s.height)
                    .append('}');
            json.append(i == surfaces.size() - 1 ? "\n" : ",\n");
        }
        json.append("  ],\n");
        json.append("  \"samples_csv\": [\n    \"t_s,cpu_pct,heap_mb,"
                + String.join(",", surfaces.stream().map(s -> "fps_" + s.index).toList())
                + "\",\n");
        for (int i = 0; i < samples.size(); i++) {
            json.append("    \"").append(samples.get(i)).append('"');
            json.append(i == samples.size() - 1 ? "\n" : ",\n");
        }
        json.append("  ]\n}\n");
        Path file = outDir.resolve("0d-metrics.json");
        Files.writeString(file, json.toString());
        System.out.println("[0d] metrics -> " + file.toAbsolutePath());
        System.out.println(json);

        StringBuilder csv = new StringBuilder("t_s,cpu_pct,heap_mb,"
                + String.join(",", surfaces.stream().map(s -> "fps_" + s.index).toList()) + "\n");
        for (String line : samples) {
            csv.append(line).append('\n');
        }
        Files.writeString(outDir.resolve("0d-samples.csv"), csv.toString());
    }

    private static String versionOf(Class<?> type) {
        Package pkg = type.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null ? "unknown" : version;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** One VLCJ callback surface plus its JavaFX ImageView and counters. */
    private final class Surface {

        final int index;
        final String url;
        final ImageView view = new ImageView();
        final javafx.scene.layout.Pane node;
        final Label caption;
        final AtomicLong frames = new AtomicLong();

        volatile int width;
        volatile int height;
        volatile int stride;
        volatile int sourceW;
        volatile int sourceH;
        volatile boolean formatLogged;
        volatile int[] bufferA;
        volatile int[] bufferB;
        volatile int[] ready;
        volatile boolean fresh;
        volatile double steadyFps;
        volatile double mediaFps;
        volatile long mediaMs = -1;

        private EmbeddedMediaPlayer player;
        private WritableImage image;
        private long firstFrameNanos;
        private long lastCountFrames;
        private long framesAtTwoSeconds = -1;
        private long mediaBaseMs = -1;
        private long mediaBaseFrames;
        private long lastSig;
        private long dupFrames;
        private boolean chromaWarned;

        Surface(int index, String url) {
            this.index = index;
            this.url = url;
            this.view.setPreserveRatio(true);
            this.view.setSmooth(true);
            this.view.setManaged(false);
            this.caption = new Label("cam" + (index + 1) + " — " + url);
            this.caption.setTextFill(Color.web("#d0d0d0"));
            this.caption.setStyle("-fx-font-size: 11px; -fx-padding: 4; -fx-background-color: rgba(0,0,0,0.55);");
            this.caption.setMouseTransparent(true);
            // A Pane, not a StackPane: binding fitWidth to a StackPane's own width feeds back into
            // its pref size and the cell grows without bound (observed: 2710x1636 cells in a
            // 1024x620 scene). An unmanaged ImageView sized in layoutChildren is stable.
            this.node = new javafx.scene.layout.Pane(view, caption) {
                @Override
                protected void layoutChildren() {
                    double w = Math.max(0, getWidth());
                    double h = Math.max(0, getHeight());
                    view.setFitWidth(w);
                    view.setFitHeight(h);
                    view.resizeRelocate(0, 0, w, h);
                    caption.autosize();
                    caption.relocate(4, 4);
                }
            };
            this.node.setMinSize(0, 0);
            this.node.setStyle("-fx-background-color: #111; -fx-border-color: #333;");
        }

        void open() {
            BufferFormatCallback format = new BufferFormatCallback() {
                @Override
                public BufferFormat getBufferFormat(int sourceWidth, int sourceHeight) {
                    sourceW = sourceWidth;
                    sourceH = sourceHeight;
                    return new RV32BufferFormat(sourceWidth, sourceHeight);
                }

                @Override
                public void newFormatSize(int oldWidth, int oldHeight, int newWidth, int newHeight) {
                    // Buffers are (re)allocated lazily in onFrame().
                }

                @Override
                public void allocatedBuffers(ByteBuffer[] buffers) {
                    // No direct access needed; the render callback receives the buffer.
                }
            };
            RenderCallback render = new RenderCallback() {
                @Override
                public void lock(MediaPlayer mediaPlayer) {
                }

                @Override
                public void display(MediaPlayer mediaPlayer, ByteBuffer[] buffers, BufferFormat format,
                                    int width, int height) {
                    onFrame(buffers[0], format, width, height);
                }

                @Override
                public void unlock(MediaPlayer mediaPlayer) {
                }
            };

            CallbackVideoSurface surface = factory.videoSurfaces().newVideoSurface(format, render,
                    Boolean.parseBoolean(System.getProperty("spike.lock", "true")));
            player = factory.mediaPlayers().newEmbeddedMediaPlayer();
            player.videoSurface().set(surface);
            player.media().play(url);
        }

        /** Called on VLC's video thread; must not touch JavaFX nodes. */
        private void onFrame(ByteBuffer buffer, BufferFormat format, int w, int h) {
            String chroma = format.getChroma();
            int[] pitches = format.getPitches();
            int[] lines = format.getLines();
            int pitch = pitches.length > 0 ? pitches[0] : w * 4;
            int lineCount = lines.length > 0 ? lines[0] : h;
            if (!formatLogged) {
                formatLogged = true;
                System.out.println("[0d] " + url + " format chroma=" + chroma + " size=" + w + "x" + h
                        + " source=" + sourceW + "x" + sourceH + " pitch=" + pitch + " lines=" + lineCount
                        + " capacity=" + buffer.capacity());
            }
            if (w <= 0 || h <= 0 || pitch < w * 4) {
                return;
            }
            int stride = pitch / 4;
            if (w != width || h != height || stride != this.stride || bufferA == null) {
                width = w;
                height = h;
                this.stride = stride;
                bufferA = new int[w * h];
                bufferB = new int[w * h];
                ready = null;
            }
            if (!"RV32".equalsIgnoreCase(chroma)) {
                if (!chromaWarned) {
                    chromaWarned = true;
                    System.err.println("[0d] unexpected chroma " + chroma + " on " + url + " — frames counted, not painted");
                }
                frames.incrementAndGet();
                return;
            }
            int[] target = (ready == bufferA) ? bufferB : bufferA;
            ByteBuffer rgb = buffer.order(ByteOrder.LITTLE_ENDIAN);
            int rows = Math.min(h, lineCount);
            if (stride == w) {
                for (int i = 0; i < w * rows; i++) {
                    int pixel = rgb.getInt(i << 2);
                    target[i] = 0xFF000000 | ((pixel & 0xFF) << 16) | (pixel & 0xFF00) | ((pixel >> 16) & 0xFF);
                }
            } else {
                for (int y = 0; y < rows; y++) {
                    int rowIn = y * stride;
                    int rowOut = y * w;
                    for (int x = 0; x < w; x++) {
                        int pixel = rgb.getInt((rowIn + x) << 2);
                        target[rowOut + x] = 0xFF000000 | ((pixel & 0xFF) << 16) | (pixel & 0xFF00) | ((pixel >> 16) & 0xFF);
                    }
                }
            }
            ready = target;
            fresh = true;
            // Cheap duplicate detector: VLCJ appears to invoke display() twice per decoded frame.
            // Sampling a fixed set of pixels (not a full compare) is enough to tell.
            int sampleCount = Math.min(w * rows, 4096);
            long sig = 1125899906842597L;
            for (int i = 0; i < sampleCount; i += 97) {
                sig = 31 * sig + target[i];
            }
            if (sig == lastSig) {
                dupFrames++;
            }
            lastSig = sig;
            if (frames.incrementAndGet() == 1) {
                firstFrameNanos = System.nanoTime();
            }
        }

        /** Called on the JavaFX thread at pulse rate. */
        void paint() {
            if (!fresh || width == 0) {
                return;
            }
            fresh = false;
            int[] src = ready;
            if (src == null) {
                return;
            }
            if (image == null || image.getWidth() != width || image.getHeight() != height) {
                image = new WritableImage(width, height);
                view.setImage(image);
            }
            image.getPixelWriter().setPixels(0, 0, width, height,
                    PixelFormat.getIntArgbInstance(), src, 0, width);
        }

        /** Instantaneous FPS since the previous sample; also maintains the steady-state average. */
        double fpsNow(double deltaSeconds) {
            long count = frames.get();
            double fps = (count - lastCountFrames) / Math.max(deltaSeconds, 0.001);
            lastCountFrames = count;
            long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
            if (framesAtTwoSeconds < 0 && elapsedMs >= 2000) {
                framesAtTwoSeconds = count;
            } else if (framesAtTwoSeconds >= 0 && elapsedMs > 3000) {
                // Steady-state average ignores the first two seconds (JIT + first-frame allocation).
                steadyFps = (count - framesAtTwoSeconds) / ((elapsedMs - 2000) / 1000d);
            }
            // Cross-check against the media clock: if this is roughly double the stream rate, the
            // render callback is firing twice per decoded frame rather than the source being fast.
            try {
                long t = player == null ? -1 : player.status().time();
                mediaMs = t;
                if (t > 0) {
                    if (mediaBaseMs < 0) {
                        mediaBaseMs = t;
                        mediaBaseFrames = count;
                    } else if (t - mediaBaseMs > 1000) {
                        mediaFps = (count - mediaBaseFrames) * 1000d / (t - mediaBaseMs);
                    }
                }
            } catch (RuntimeException ignored) {
                // Player released concurrently at the end of the run.
            }
            return fps;
        }

        long firstFrameMillis() {
            return firstFrameNanos == 0 ? -1 : (firstFrameNanos - startedNanos) / 1_000_000L;
        }

        void close() {
            try {
                if (player != null) {
                    player.controls().stop();
                    player.release();
                }
            } catch (Exception e) {
                System.err.println("[0d] release failed for " + url + ": " + e);
            }
        }
    }
}

