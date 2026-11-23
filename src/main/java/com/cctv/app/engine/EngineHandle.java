package com.cctv.app.engine;

import com.cctv.contract.EventBus;
import com.cctv.contract.Frame;
import com.cctv.engine.Camera1Pipeline;
import com.cctv.engine.FfmpegCamera1Pipeline;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * App-side handle for one camera's engine pipeline.
 *
 * <p>Two engine shapes exist and only one may ever open the main stream:
 * <ul>
 *   <li>{@link FfmpegCamera1Pipeline} — one FFmpeg process that packet-copies MKV segments and
 *       supplies display frames; its AI worker is optional for record-only cameras.</li>
 *   <li>{@link Camera1Pipeline} — JavaCV capture for display + AI when recording is disabled.</li>
 * </ul>
 *
 * <p>The handle keeps the rest of the app (display tap, status badges, shutdown) independent of which
 * shape is active. Creation is synchronous and may load the ONNX model when AI is enabled, so call it off the JavaFX thread.
 */
public final class EngineHandle implements AutoCloseable {

    private enum Mode { RECORDING, ANALYSIS }

    private final Mode mode;
    private final Camera1Pipeline analysis;
    private final FfmpegCamera1Pipeline recording;
    private volatile boolean closed;

    private EngineHandle(Camera1Pipeline analysis, FfmpegCamera1Pipeline recording) {
        this.analysis = analysis;
        this.recording = recording;
        this.mode = recording != null ? Mode.RECORDING : Mode.ANALYSIS;
    }

    /**
     * Creates the pipeline the camera configuration asks for. Recording and AI flags are independent;
     * a record-only camera does not load or run an ONNX model.
     */
    public static EngineHandle create(long cameraId, java.net.URI mainUrl, Path modelPath,
                                      Path recordingsDir, int segmentSeconds, boolean record,
                                      EventBus bus, double aiRateFps)
            throws IOException, ai.onnxruntime.OrtException {
        return create(cameraId, mainUrl, modelPath, recordingsDir, segmentSeconds, record, bus,
                aiRateFps, null);
    }

    /** Phase 7b: same, plus the app's counting-line source for the engine's crossing detector. */
    public static EngineHandle create(long cameraId, java.net.URI mainUrl, Path modelPath,
                                      Path recordingsDir, int segmentSeconds, boolean record,
                                      EventBus bus, double aiRateFps,
                                      com.cctv.contract.LineSource lines)
            throws IOException, ai.onnxruntime.OrtException {
        return create(cameraId, mainUrl, modelPath, recordingsDir, segmentSeconds, record, bus,
                aiRateFps, lines, null);
    }

    /**
     * Phase 5b (engine-owned zones): same, plus the app's cached zone source so the engine decides
     * intrusions (feet point, two consecutive observations, one event per entry) instead of the
     * transitional app-side confirmer.
     */
    public static EngineHandle create(long cameraId, java.net.URI mainUrl, Path modelPath,
                                      Path recordingsDir, int segmentSeconds, boolean record,
                                      EventBus bus, double aiRateFps,
                                      com.cctv.contract.LineSource lines,
                                      com.cctv.contract.ZoneSource zones)
            throws IOException, ai.onnxruntime.OrtException {
        return create(cameraId, mainUrl, modelPath, recordingsDir, segmentSeconds, record, true,
                bus, aiRateFps, lines, zones);
    }

    /** Creates a per-camera runtime with independent recording and AI enablement. */
    public static EngineHandle create(long cameraId, java.net.URI mainUrl, Path modelPath,
                                      Path recordingsDir, int segmentSeconds, boolean record,
                                      boolean ai, EventBus bus, double aiRateFps,
                                      com.cctv.contract.LineSource lines,
                                      com.cctv.contract.ZoneSource zones)
            throws IOException, ai.onnxruntime.OrtException {
        com.cctv.contract.LineSource source = lines == null
                ? camera -> java.util.List.of() : lines;
        com.cctv.contract.ZoneSource zoneSource = zones == null
                ? camera -> java.util.List.of() : zones;
        if (!record && !ai) {
            throw new IllegalArgumentException("a camera without recording or AI does not need an engine runtime");
        }
        if (record) {
            FfmpegCamera1Pipeline pipeline = FfmpegCamera1Pipeline.create(cameraId, mainUrl, modelPath,
                    recordingsDir, segmentSeconds, bus, aiRateFps, source, zoneSource, ai);
            return new EngineHandle(null, pipeline);
        }
        return new EngineHandle(Camera1Pipeline.create(cameraId, mainUrl, modelPath, bus,
                aiRateFps, source, zoneSource), null);
    }

    public boolean isRecording() {
        return mode == Mode.RECORDING;
    }

    public void start() {
        if (closed) {
            throw new IllegalStateException("engine handle is closed");
        }
        if (recording != null) {
            recording.start();
        } else {
            analysis.start();
        }
    }

    /** Newest frame for the wall (deep copy); empty until the first decoded frame arrives. */
    public Optional<Frame> latestFrame() {
        if (closed) {
            return Optional.empty();
        }
        return recording != null ? recording.latestFrame() : analysis.latestFrame();
    }

    public boolean isRunning() {
        if (closed) {
            return false;
        }
        return recording != null ? recording.isRunning() : analysis.isRunning();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (recording != null) {
            recording.close();
        }
        if (analysis != null) {
            analysis.close();
        }
    }
}
