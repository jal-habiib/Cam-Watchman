package com.cctv.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import com.cctv.contract.Detection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * YOLO11 detector on ONNX Runtime (CPU).
 *
 * <p>Everything size-related comes from the model itself: the input tensor shape is read
 * from the session so a 320 or 416 export works unchanged (plan p.1: "tensor shape read
 * from the model, not hardcoded"), and the output shape drives
 * {@link YoloPostprocessor#decode}. Loading validates that the input is a static
 * {@code [1,3,H,W]} tensor and fails with an actionable message if it is not.
 *
 * <p>Inference is blocking: callers must not run it on the JavaFX thread (plan p.3 rule 2).
 * {@link OrtSession#run} is thread-safe; each call allocates its own input tensor.
 */
public final class YoloDetector implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(YoloDetector.class);

    /** One inference with its timings (nanoseconds) so 2e can report real numbers. */
    public record Inference(List<Detection> detections, int inputWidth, int inputHeight,
                            long[] outputShape, long preprocessNanos, long runNanos,
                            long postprocessNanos) {

        public long totalNanos() {
            return preprocessNanos + runNanos + postprocessNanos;
        }
    }

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final int inputWidth;
    private final int inputHeight;
    private final long[] outputShape;
    private final float confThreshold;
    private final float nmsIou;

    private YoloDetector(OrtEnvironment env, OrtSession session, String inputName,
                         int inputWidth, int inputHeight, long[] outputShape,
                         float confThreshold, float nmsIou) {
        this.env = env;
        this.session = session;
        this.inputName = inputName;
        this.inputWidth = inputWidth;
        this.inputHeight = inputHeight;
        this.outputShape = outputShape;
        this.confThreshold = confThreshold;
        this.nmsIou = nmsIou;
    }

    /**
     * Loads the session, reads its shapes and applies the plan defaults
     * (confidence 0.5, NMS IoU 0.45).
     */
    public static YoloDetector load(Path modelPath) throws OrtException, IOException {
        return load(modelPath, 2, YoloPostprocessor.DEFAULT_CONF_THRESHOLD,
                YoloPostprocessor.DEFAULT_NMS_IOU);
    }

    /**
     * @param threads       intra-op threads; keep small on the 8 GB laptop so the UI and the
     *                      decoder still get CPU. 0 leaves ONNX Runtime's default.
     * @param confThreshold keep boxes with class score &gt;= this
     * @param nmsIou        drop later boxes with IoU &gt; this
     */
    public static YoloDetector load(Path modelPath, int threads,
                                    float confThreshold, float nmsIou)
            throws OrtException, IOException {
        if (modelPath == null || !Files.isRegularFile(modelPath)) {
            throw new IOException("model not found: " + modelPath
                    + " (expected models/yolo11n.onnx; see models/README.md)");
        }
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        if (threads > 0) {
            options.setIntraOpNumThreads(threads);
            options.setInterOpNumThreads(1);
        }
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        OrtSession session = env.createSession(modelPath.toString(), options);

        String inputName = null;
        long[] inputShape = null;
        for (Map.Entry<String, ai.onnxruntime.NodeInfo> e : session.getInputInfo().entrySet()) {
            if (e.getValue().getInfo() instanceof TensorInfo ti) {
                inputName = e.getKey();
                inputShape = ti.getShape();
                break;
            }
        }
        if (inputName == null || inputShape == null) {
            session.close();
            throw new IllegalArgumentException("model has no tensor input: " + modelPath);
        }
        if (inputShape.length != 4 || inputShape[1] != 3) {
            session.close();
            throw new IllegalArgumentException(
                    "expected NCHW [1,3,H,W] input, model says "
                            + java.util.Arrays.toString(inputShape));
        }
        if (inputShape[2] <= 0 || inputShape[3] <= 0) {
            session.close();
            throw new IllegalArgumentException(
                    "model input is dynamic " + java.util.Arrays.toString(inputShape)
                            + "; export a fixed imgsz (e.g. imgsz=320) so boxes map correctly");
        }
        int inputHeight = (int) inputShape[2];
        int inputWidth = (int) inputShape[3];

        long[] outputShape = null;
        for (ai.onnxruntime.NodeInfo ni : session.getOutputInfo().values()) {
            if (ni.getInfo() instanceof TensorInfo ti) {
                outputShape = ti.getShape();
                break;
            }
        }
        if (outputShape == null) {
            session.close();
            throw new IllegalArgumentException("model has no tensor output: " + modelPath);
        }
        log.info("ONNX model {} input={} output={}", modelPath.getFileName(),
                java.util.Arrays.toString(inputShape), java.util.Arrays.toString(outputShape));
        return new YoloDetector(env, session, inputName, inputWidth, inputHeight,
                outputShape, confThreshold, nmsIou);
    }

    public int inputWidth() {
        return inputWidth;
    }

    public int inputHeight() {
        return inputHeight;
    }

    /** Output shape as declared by the model, e.g. {@code [1, 84, 2100]} for 320px. */
    public long[] outputShape() {
        return outputShape.clone();
    }

    public float confThreshold() {
        return confThreshold;
    }

    public float nmsIou() {
        return nmsIou;
    }

    /** Detections only; see {@link #detectTimed} for timings. */
    public List<Detection> detect(BufferedImage image, long cameraId, long frameUtcMs) {
        return detectTimed(image, cameraId, frameUtcMs).detections();
    }

    /** Runs one frame and returns detections in normalized 0..1 coordinates of the frame. */
    public Inference detectTimed(BufferedImage image, long cameraId, long frameUtcMs) {
        if (image == null) {
            throw new IllegalArgumentException("image required");
        }
        long t0 = System.nanoTime();
        YoloPreprocessor.Preprocessed pp =
                YoloPreprocessor.preprocess(image, inputWidth, inputHeight);
        long t1 = System.nanoTime();

        float[] output;
        long[] shape;
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(pp.nchw()),
                new long[] {1, 3, inputHeight, inputWidth})) {
            try (OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
                ai.onnxruntime.OnnxValue outputValue = result.get(0);
                if (!(outputValue instanceof OnnxTensor out)) {
                    throw new IllegalStateException("model's first output is not a tensor");
                }
                FloatBuffer fb = out.getFloatBuffer();
                output = new float[fb.remaining()];
                fb.get(output);
                shape = ((TensorInfo) out.getInfo()).getShape();
            }
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX inference failed", e);
        }
        long t2 = System.nanoTime();

        List<Detection> detections = YoloPostprocessor.decode(output, shape, pp.letterbox(),
                image.getWidth(), image.getHeight(), cameraId, frameUtcMs,
                confThreshold, nmsIou);
        long t3 = System.nanoTime();
        return new Inference(detections, inputWidth, inputHeight, shape,
                t1 - t0, t2 - t1, t3 - t2);
    }

    /** Convenience for the spike: read an image file and detect on it. */
    public List<Detection> detectFile(Path imagePath, long cameraId) throws IOException {
        BufferedImage image = javax.imageio.ImageIO.read(imagePath.toFile());
        if (image == null) {
            throw new IOException("not a readable image: " + imagePath);
        }
        return detect(image, cameraId, System.currentTimeMillis());
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            log.warn("closing ONNX session failed", e);
        }
        // OrtEnvironment is a process-wide singleton; leave it to the JVM.
    }

    /** Optional accessor used by tests/UI that need the raw session. */
    public OrtSession session() {
        return session;
    }

    /** Optional accessor; never null once loaded. */
    public Optional<String> inputName() {
        return Optional.of(inputName);
    }
}
