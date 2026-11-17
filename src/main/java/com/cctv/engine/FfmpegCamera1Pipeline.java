package com.cctv.engine;

import ai.onnxruntime.OrtException;
import com.cctv.ai.YoloDetector;
import com.cctv.ai.YoloPostprocessor;
import com.cctv.contract.EventBus;
import com.cctv.contract.Frame;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Camera 1 single-input production pipeline. One FFmpeg process opens RTSP once, copies
 * compressed packets into MKV segments, and emits downscaled raw frames to the YOLO worker.
 *
 * <p>Construct away from the JavaFX thread because model loading is synchronous. The caller
 * owns this object and must close it during application shutdown.
 */
public final class FfmpegCamera1Pipeline implements AutoCloseable {
    private final FfmpegCamera1Process source;
    private final Camera1AiWorker ai;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private FfmpegCamera1Pipeline(FfmpegCamera1Process source, Camera1AiWorker ai) {
        this.source = source;
        this.ai = ai;
    }

    public static FfmpegCamera1Pipeline create(long cameraId, URI mainUrl, Path modelPath,
                                               Path recordingsDir, int segmentSeconds,
                                               EventBus events, double aiRateFps)
            throws IOException, OrtException {
        return create(cameraId, mainUrl, modelPath, recordingsDir, segmentSeconds, events,
                aiRateFps, camera -> java.util.List.of());
    }

    /** Phase 7b: recording pipeline plus the app's cached counting lines. */
    public static FfmpegCamera1Pipeline create(long cameraId, URI mainUrl, Path modelPath,
                                               Path recordingsDir, int segmentSeconds,
                                               EventBus events, double aiRateFps,
                                               com.cctv.contract.LineSource lines)
            throws IOException, OrtException {
        return create(cameraId, mainUrl, modelPath, recordingsDir, segmentSeconds, events,
                aiRateFps, lines, camera -> java.util.List.of());
    }

    /**
     * Phase 5b (engine-owned): recording pipeline plus cached counting lines and active zones, so
     * the worker publishes {@code LineCrossingEvent}s and {@code IntrusionEvent}s itself.
     */
    public static FfmpegCamera1Pipeline create(long cameraId, URI mainUrl, Path modelPath,
                                               Path recordingsDir, int segmentSeconds,
                                               EventBus events, double aiRateFps,
                                               com.cctv.contract.LineSource lines,
                                               com.cctv.contract.ZoneSource zones)
            throws IOException, OrtException {
        Objects.requireNonNull(mainUrl, "mainUrl");
        Objects.requireNonNull(modelPath, "modelPath");
        Objects.requireNonNull(recordingsDir, "recordingsDir");
        Objects.requireNonNull(events, "events");
        if (!"rtsp".equalsIgnoreCase(mainUrl.getScheme())) {
            throw new IllegalArgumentException("Camera 1 main URL must use RTSP");
        }

        FfmpegCamera1Process source = new FfmpegCamera1Process(cameraId, mainUrl.toString(),
                recordingsDir, segmentSeconds, aiRateFps, events);
        YoloDetector detector;
        try {
            detector = YoloDetector.load(modelPath, 2,
                    YoloPostprocessor.DEFAULT_CONF_THRESHOLD,
                    YoloPostprocessor.DEFAULT_NMS_IOU);
        } catch (IOException | OrtException | RuntimeException modelFailure) {
            source.close();
            throw modelFailure;
        }
        try {
            Camera1AiWorker worker = new Camera1AiWorker(cameraId, source, detector, events,
                    aiRateFps, lines, zones);
            return new FfmpegCamera1Pipeline(source, worker);
        } catch (RuntimeException invalidConfiguration) {
            detector.close();
            source.close();
            throw invalidConfiguration;
        }
    }

    /** Starts the one-input FFmpeg process before the inference consumer. */
    public void start() {
        if (closed.get()) throw new IllegalStateException("pipeline is closed");
        if (!started.compareAndSet(false, true)) return;
        try {
            source.start();
            ai.start();
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    /** Latest decoded frame for the Camera 1 JavaFX surface; does not consume the AI frame slot. */
    public Optional<Frame> latestFrame() {
        return source.latestFrame();
    }

    public boolean isRunning() {
        return started.get() && !closed.get() && source.isRunning() && ai.isRunning();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ai.close();
        source.close();
    }
}
