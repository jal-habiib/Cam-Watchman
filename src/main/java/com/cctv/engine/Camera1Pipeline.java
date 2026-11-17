package com.cctv.engine;

import com.cctv.ai.YoloDetector;
import com.cctv.contract.EventBus;
import com.cctv.contract.Frame;
import ai.onnxruntime.OrtException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the Camera 1 capture-to-AI path. One FFmpeg grabber feeds a latest-frame handoff;
 * the CPU YOLO worker samples that handoff at the configured inference rate.
 *
 * <p>Construct this away from the JavaFX thread because ONNX model loading is synchronous.
 * The application owns the returned pipeline and must close it during shutdown.
 */
public final class Camera1Pipeline implements AutoCloseable {
    private final Camera1StreamManager stream;
    private final Camera1AiWorker ai;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private Camera1Pipeline(Camera1StreamManager stream, Camera1AiWorker ai) {
        this.stream = stream;
        this.ai = ai;
    }

    public static Camera1Pipeline create(long cameraId, URI mainUrl, Path modelPath,
                                         EventBus events, double aiRateFps)
            throws IOException, OrtException {
        return create(cameraId, mainUrl, modelPath, events, aiRateFps, camera -> java.util.List.of());
    }

    /** Phase 7b: same pipeline with the app's cached counting lines for crossing detection. */
    public static Camera1Pipeline create(long cameraId, URI mainUrl, Path modelPath,
                                         EventBus events, double aiRateFps,
                                         com.cctv.contract.LineSource lines)
            throws IOException, OrtException {
        return create(cameraId, mainUrl, modelPath, events, aiRateFps, lines, camera -> java.util.List.of());
    }

    /**
     * Phase 5b (engine-owned): same pipeline plus the app's cached active zones, so the worker can
     * publish {@code IntrusionEvent}s instead of the app guessing zones from detections.
     */
    public static Camera1Pipeline create(long cameraId, URI mainUrl, Path modelPath,
                                         EventBus events, double aiRateFps,
                                         com.cctv.contract.LineSource lines,
                                         com.cctv.contract.ZoneSource zones)
            throws IOException, OrtException {
        Objects.requireNonNull(mainUrl, "mainUrl");
        Objects.requireNonNull(modelPath, "modelPath");
        Objects.requireNonNull(events, "events");
        if (cameraId <= 0) throw new IllegalArgumentException("cameraId must be positive");
        if (!(aiRateFps > 0.0) || aiRateFps > 60.0 || !Double.isFinite(aiRateFps)) {
            throw new IllegalArgumentException("aiRateFps must be in (0, 60]");
        }

        Camera1StreamManager stream = new Camera1StreamManager(cameraId, mainUrl, events);
        YoloDetector detector = YoloDetector.load(modelPath, 2,
                com.cctv.ai.YoloPostprocessor.DEFAULT_CONF_THRESHOLD,
                com.cctv.ai.YoloPostprocessor.DEFAULT_NMS_IOU);
        try {
            Camera1AiWorker worker = new Camera1AiWorker(cameraId, stream, detector, events,
                    aiRateFps, lines, zones);
            return new Camera1Pipeline(stream, worker);
        } catch (RuntimeException invalidConfiguration) {
            detector.close();
            stream.close();
            throw invalidConfiguration;
        }
    }

    /** Starts capture before the AI consumer. Safe to call once. */
    public void start() {
        if (closed.get()) throw new IllegalStateException("pipeline is closed");
        if (!started.compareAndSet(false, true)) return;
        try {
            stream.start();
            ai.start();
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    /** Non-destructive display tap; returns an isolated image copy without consuming AI frames. */
    public Optional<Frame> latestFrame() {
        return stream.latestFrame();
    }

    /** Copy only if a frame newer than lastCapturedUtcMs is available. */
    public Optional<Frame> latestFrameAfter(long lastCapturedUtcMs) {
        return stream.latestFrameAfter(lastCapturedUtcMs);
    }

    public boolean isRunning() {
        return started.get() && !closed.get() && stream.isRunning() && ai.isRunning();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        // Stop inference first so it cannot consume frames while capture is being torn down.
        ai.close();
        stream.close();
    }
}
