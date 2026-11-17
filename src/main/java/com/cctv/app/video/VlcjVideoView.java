package com.cctv.app.video;

import com.cctv.contract.StreamState;
import com.cctv.contract.StreamStatusEvent;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;
import javafx.animation.AnimationTimer;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat;

/**
 * One display-only camera cell: a VLCJ callback surface (RV32 buffers) painted into a JavaFX
 * {@link ImageView}. This is the 0d spike turned into production code: buffers are converted on
 * VLC's thread into an int[] ARGB frame, and an {@link AnimationTimer} copies the newest frame to
 * the image on the FX thread, so VLC never touches JavaFX nodes.
 */
public final class VlcjVideoView extends Pane {

    private static final Logger log = LoggerFactory.getLogger(VlcjVideoView.class);

    private final String url;
    private final Consumer<StreamStatusEvent> publisher;
    private final long cameraId;
    private final ImageView view = new ImageView();
    private final AnimationTimer painter;

    private EmbeddedMediaPlayer player;
    private WritableImage image;
    private volatile int[] bufferA;
    private volatile int[] bufferB;
    private volatile int[] ready;
    private volatile boolean fresh;
    private volatile int width;
    private volatile int height;
    private volatile int stride;
    private volatile boolean announced;
    private volatile boolean online;
    private volatile long lastFrameNanos;
    private volatile long lastReconnectNanos;

    public VlcjVideoView(long cameraId, java.net.URI uri, Consumer<StreamStatusEvent> publisher) {
        this.cameraId = cameraId;
        this.url = uri.toString();
        this.publisher = publisher;
        this.view.setPreserveRatio(true);
        this.view.setSmooth(true);
        this.view.setManaged(false);
        getChildren().add(view);
        setMinSize(0, 0);
        setStyle("-fx-background-color:#0b0b0b;");
        this.painter = new AnimationTimer() {
            @Override
            public void handle(long now) {
                paint();
            }
        };
    }

    public void start() {
        if (!VlcjRuntime.available()) {
            fail("VLC native libraries not found");
            return;
        }
        try {
            BufferFormatCallback formatCallback = new BufferFormatCallback() {
                @Override
                public BufferFormat getBufferFormat(int sourceWidth, int sourceHeight) {
                    return new RV32BufferFormat(sourceWidth, sourceHeight);
                }

                @Override
                public void newFormatSize(int oldWidth, int oldHeight, int newWidth, int newHeight) {
                    // buffers are reallocated in onFrame when the size changes
                }

                @Override
                public void allocatedBuffers(ByteBuffer[] buffers) {
                    // the render callback receives the same buffers
                }
            };
            RenderCallback renderCallback = new RenderCallback() {
                @Override
                public void lock(MediaPlayer mediaPlayer) {
                }

                @Override
                public void display(MediaPlayer mediaPlayer, ByteBuffer[] buffers, BufferFormat format,
                                    int w, int h) {
                    onFrame(buffers[0], format, w, h);
                }

                @Override
                public void unlock(MediaPlayer mediaPlayer) {
                }
            };
            CallbackVideoSurface surface = VlcjRuntime.factory().videoSurfaces()
                    .newVideoSurface(formatCallback, renderCallback, false);
            player = VlcjRuntime.factory().mediaPlayers().newEmbeddedMediaPlayer();
            player.videoSurface().set(surface);
            player.media().play(url);
            painter.start();
            log.info("camera {} display-only stream started ({})", cameraId, com.cctv.app.config.Redaction.redact(url));
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            log.error("camera {} could not start VLCJ playback", cameraId, e);
            fail("could not start VLC playback");
        }
    }

    /** Called on VLC's video thread. */
    private void onFrame(ByteBuffer buffer, BufferFormat format, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int[] pitches = format.getPitches();
        int[] lines = format.getLines();
        int pitch = pitches.length > 0 ? pitches[0] : w * 4;
        int lineCount = lines.length > 0 ? lines[0] : h;
        if (pitch < w * 4) {
            return;
        }
        int rowStride = pitch / 4;
        if (w != width || h != height || rowStride != stride || bufferA == null) {
            width = w;
            height = h;
            stride = rowStride;
            bufferA = new int[w * h];
            bufferB = new int[w * h];
            ready = null;
        }
        if (!"RV32".equalsIgnoreCase(format.getChroma())) {
            return;
        }
        int[] target = ready == bufferA ? bufferB : bufferA;
        ByteBuffer rgb = buffer.order(ByteOrder.LITTLE_ENDIAN);
        int rows = Math.min(h, lineCount);
        if (rowStride == w) {
            for (int i = 0; i < w * rows; i++) {
                int pixel = rgb.getInt(i << 2);
                target[i] = 0xFF000000 | ((pixel & 0xFF) << 16) | (pixel & 0xFF00) | ((pixel >> 16) & 0xFF);
            }
        } else {
            for (int y = 0; y < rows; y++) {
                int rowIn = y * rowStride;
                int rowOut = y * w;
                for (int x = 0; x < w; x++) {
                    int pixel = rgb.getInt((rowIn + x) << 2);
                    target[rowOut + x] = 0xFF000000 | ((pixel & 0xFF) << 16) | (pixel & 0xFF00)
                            | ((pixel >> 16) & 0xFF);
                }
            }
        }
        ready = target;
        fresh = true;
        lastFrameNanos = System.nanoTime();
        if (!announced) {
            announced = true;
            online = true;
            publish(StreamState.ONLINE, "display link live");
        } else if (!online) {
            online = true;
            publish(StreamState.ONLINE, "display recovered");
        }
    }

    private static final long STALL_NANOS = 10_000_000_000L;
    private static final long RECONNECT_NANOS = 10_000_000_000L;

    /** FX thread: copy the newest converted frame into the image. */
    private void paint() {
        // Stale-frame guard (plan 1a/6a): an open socket that stops delivering frames is offline.
        long now = System.nanoTime();
        if (online && now - lastFrameNanos > STALL_NANOS) {
            online = false;
            publish(StreamState.OFFLINE, "display stalled (no frames for 10 s)");
        }
        if (!online && announced && now - lastReconnectNanos > RECONNECT_NANOS && player != null) {
            lastReconnectNanos = now;
            log.info("camera {} reconnecting to {}", cameraId,
                    com.cctv.app.config.Redaction.redact(url));
            try {
                player.media().play(url);
            } catch (RuntimeException e) {
                log.warn("camera {} reconnect failed: {}", cameraId, e.toString());
            }
        }
        int[] source = ready;
        if (source == null || !fresh) {
            return;
        }
        int w = width;
        int h = height;
        if (w <= 0 || h <= 0) {
            return;
        }
        if (image == null || image.getWidth() != w || image.getHeight() != h) {
            image = new WritableImage(w, h);
            view.setImage(image);
        }
        image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), source, 0, w);
        fresh = false;
        view.setFitWidth(getWidth());
        view.setFitHeight(getHeight());
        view.relocate((getWidth() - view.getLayoutBounds().getWidth()) / 2,
                (getHeight() - view.getLayoutBounds().getHeight()) / 2);
    }

    private void fail(String detail) {
        publish(StreamState.OFFLINE, detail);
    }

    /**
     * Phase 5c: a still copy of the newest displayed frame, for the zone editor. Empty until the
     * first frame arrives; the copy keeps the editor independent of the live callback buffers.
     */
    public java.util.Optional<java.awt.image.BufferedImage> snapshotImage() {
        int[] pixels = ready;
        int w = width;
        int h = height;
        if (pixels == null || w <= 0 || h <= 0) {
            return java.util.Optional.empty();
        }
        int[] copy = pixels.clone();
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(w, h,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, w, h, copy, 0, w);
        return java.util.Optional.of(image);
    }

    private void publish(StreamState state, String detail) {
        if (publisher != null) {
            publisher.accept(StreamStatusEvent.now(cameraId, state, detail));
        }
    }

    public void close() {
        painter.stop();
        if (player != null) {
            try {
                player.controls().stop();
                player.release();
            } catch (RuntimeException e) {
                log.warn("camera {} VLCJ player close failed: {}", cameraId, e.toString());
            }
            player = null;
        }
        view.setImage(null);
        log.info("camera {} display-only stream closed", cameraId);
    }

}
