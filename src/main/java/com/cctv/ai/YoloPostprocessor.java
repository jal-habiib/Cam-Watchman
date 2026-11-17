package com.cctv.ai;

import com.cctv.contract.Detection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * YOLO11 decode: raw ONNX output -> filtered, NMS-suppressed {@link Detection}s in
 * normalized 0..1 frame coordinates.
 *
 * <p>Expected ONNX output for the Ultralytics export is {@code [1, 4+nc, anchors]} (or the
 * transposed {@code [1, anchors, 4+nc]}); the first four channels are box
 * {@code cx, cy, w, h} in letterboxed-input pixels (or 0..1 if the export normalizes), the
 * rest are per-class probabilities (no separate objectness). This class reads the shape,
 * so neither anchor count nor class count is hardcoded: the model can be exported at 320
 * or 416 without touching it.
 *
 * <p>Plan rules (2c): confidence 0.5, classes person/car/truck/motorcycle, NMS IoU 0.45,
 * boxes mapped back to the original frame and normalized.
 */
public final class YoloPostprocessor {

    public static final float DEFAULT_CONF_THRESHOLD = 0.5f;
    public static final float DEFAULT_NMS_IOU = 0.45f;

    /** COCO class ids (Ultralytics yolo11n export order). */
    public static final int COCO_PERSON = 0;
    public static final int COCO_BICYCLE = 1;
    public static final int COCO_CAR = 2;
    public static final int COCO_MOTORCYCLE = 3;
    public static final int COCO_BUS = 5;
    public static final int COCO_TRUCK = 7;

    /** The four classes the plan keeps; everything else is dropped after decode. */
    private static final Map<Integer, String> CLASS_FILTER = Map.of(
            COCO_PERSON, "person",
            COCO_CAR, "car",
            COCO_MOTORCYCLE, "motorcycle",
            COCO_TRUCK, "truck");

    /** One decoded box before NMS, in normalized original-frame coordinates. */
    public record Candidate(int classId, String objectClass, float confidence,
                            double x1, double y1, double x2, double y2) {

        public Candidate {
            if (objectClass == null || objectClass.isBlank()) {
                throw new IllegalArgumentException("objectClass required");
            }
            if (!Float.isFinite(confidence) || confidence < 0f || confidence > 1f) {
                throw new IllegalArgumentException("confidence out of range: " + confidence);
            }
            if (!Double.isFinite(x1) || !Double.isFinite(y1)
                    || !Double.isFinite(x2) || !Double.isFinite(y2)
                    || x1 < 0d || y1 < 0d || x2 > 1d || y2 > 1d
                    || x2 < x1 || y2 < y1) {
                throw new IllegalArgumentException("unordered box: " + x1 + "," + y1 + "," + x2 + "," + y2);
            }
        }

        public double area() {
            return Math.max(0d, x2 - x1) * Math.max(0d, y2 - y1);
        }

        public double cx() {
            return (x1 + x2) / 2d;
        }

        public double cy() {
            return (y1 + y2) / 2d;
        }

        public Detection toDetection(long cameraId, long frameUtcMs) {
            return new Detection(cameraId, objectClass, confidence,
                    x1, y1, x2, y2, frameUtcMs);
        }
    }

    private YoloPostprocessor() {
    }

    /** Whether this COCO id survives the plan's class filter. */
    public static boolean isKeepClass(int classId) {
        return CLASS_FILTER.containsKey(classId);
    }

    /** COCO label for a kept class, or null. */
    public static String labelFor(int classId) {
        return CLASS_FILTER.get(classId);
    }

    /**
     * Full pipeline: raw output -> candidates -> NMS -> {@link Detection}s.
     *
     * @param output     flat tensor data ({@code shape} product elements)
     * @param shape      model output shape, e.g. {@code [1, 84, 2100]}
     * @param lb         letterbox used for the input image
     * @param origWidth  original frame width in pixels
     * @param origHeight original frame height in pixels
     */
    public static List<Detection> decode(float[] output, long[] shape,
                                         YoloPreprocessor.Letterbox lb,
                                         int origWidth, int origHeight,
                                         long cameraId, long frameUtcMs,
                                         float confThreshold, float nmsIou) {
        List<Candidate> candidates = decodeCandidates(output, shape, lb, origWidth, origHeight,
                confThreshold);
        List<Candidate> kept = nms(candidates, nmsIou);
        List<Detection> detections = new ArrayList<>(kept.size());
        for (Candidate c : kept) {
            detections.add(c.toDetection(cameraId, frameUtcMs));
        }
        return detections;
    }

    /** {@link #decode} with the plan's defaults (conf 0.5, NMS IoU 0.45). */
    public static List<Detection> decode(float[] output, long[] shape,
                                         YoloPreprocessor.Letterbox lb,
                                         int origWidth, int origHeight,
                                         long cameraId, long frameUtcMs) {
        return decode(output, shape, lb, origWidth, origHeight, cameraId, frameUtcMs,
                DEFAULT_CONF_THRESHOLD, DEFAULT_NMS_IOU);
    }

    /**
     * Decodes the raw tensor into class-filtered, confidence-filtered candidates (no NMS).
     * Kept public so tests can exercise the geometry without a model.
     */
    public static List<Candidate> decodeCandidates(float[] output, long[] shape,
                                                   YoloPreprocessor.Letterbox lb,
                                                   int origWidth, int origHeight,
                                                   float confThreshold) {
        if (output == null || shape == null) {
            throw new IllegalArgumentException("output and shape required");
        }
        if (origWidth <= 0 || origHeight <= 0) {
            throw new IllegalArgumentException("bad original size: " + origWidth + "x" + origHeight);
        }
        if (!Float.isFinite(confThreshold) || confThreshold < 0f || confThreshold > 1f) {
            throw new IllegalArgumentException("confidence threshold must be in [0,1]");
        }
        long channels;
        long anchors;
        boolean channelsFirst;
        if (shape.length == 2) {
            channelsFirst = isChannelsFirst(shape[0], shape[1]);
            channels = channelsFirst ? shape[0] : shape[1];
            anchors = channelsFirst ? shape[1] : shape[0];
        } else if (shape.length == 3) {
            if (shape[0] != 1) {
                throw new IllegalArgumentException("expected batch size 1, got "
                        + java.util.Arrays.toString(shape));
            }
            long d1 = shape[1];
            long d2 = shape[2];
            if (d1 <= 0 || d2 <= 0) {
                throw new IllegalArgumentException("bad output shape: " + java.util.Arrays.toString(shape));
            }
            channelsFirst = isChannelsFirst(d1, d2);
            channels = channelsFirst ? d1 : d2;
            anchors = channelsFirst ? d2 : d1;
        } else {
            throw new IllegalArgumentException("unexpected output rank " + shape.length
                    + ": " + java.util.Arrays.toString(shape));
        }
        if (channels < 5) {
            throw new IllegalArgumentException("output has " + channels
                    + " channels, expected >= 5 (4 box + classes)");
        }
        if (anchors <= 0 || channels > Integer.MAX_VALUE || anchors > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid output dimensions: channels="
                    + channels + ", anchors=" + anchors);
        }
        long expected = channels * anchors;
        if (output.length != expected) {
            throw new IllegalArgumentException("output has " + output.length
                    + " values, shape needs " + expected);
        }
        final int nc = (int) (channels - 4);

        // Some exports emit 0..1 boxes instead of pixel boxes; detect from the data itself.
        final float coordLimit = normalizedBoxes(output, channelsFirst, (int) channels, (int) anchors)
                ? 1f : 0f;

        List<Candidate> candidates = new ArrayList<>();
        for (int a = 0; a < anchors; a++) {
            int bestClass = -1;
            float bestScore = -1f;
            for (int c = 0; c < nc; c++) {
                if (!isKeepClass(c)) {
                    continue;
                }
                float score = value(output, channelsFirst, (int) channels, (int) anchors, a, 4 + c);
                if (Float.isFinite(score) && score >= confThreshold
                        && score <= 1f && score > bestScore) {
                    bestScore = score;
                    bestClass = c;
                }
            }
            if (bestClass < 0) {
                continue;
            }
            float cx = value(output, channelsFirst, (int) channels, (int) anchors, a, 0);
            float cy = value(output, channelsFirst, (int) channels, (int) anchors, a, 1);
            float w = value(output, channelsFirst, (int) channels, (int) anchors, a, 2);
            float h = value(output, channelsFirst, (int) channels, (int) anchors, a, 3);
            if (!Float.isFinite(cx) || !Float.isFinite(cy)
                    || !Float.isFinite(w) || !Float.isFinite(h) || w <= 0f || h <= 0f) {
                continue;
            }
            if (coordLimit == 1f) {
                cx *= lb.dstWidth();
                cy *= lb.dstHeight();
                w *= lb.dstWidth();
                h *= lb.dstHeight();
            }
            double[] box = mapBox(cx, cy, w, h, lb, origWidth, origHeight);
            if (box == null) {
                continue;
            }
            candidates.add(new Candidate(bestClass, CLASS_FILTER.get(bestClass), bestScore,
                    box[0], box[1], box[2], box[3]));
        }
        return candidates;
    }

    /**
     * Infers the exported YOLO layout. Real YOLO channel counts are small (for this project,
     * 84 = 4 box values + 80 COCO classes), while anchor counts are normally much larger.
     * The explicit small-dimension checks also keep one-anchor synthetic/test tensors valid.
     */
    private static boolean isChannelsFirst(long first, long second) {
        if (first >= 5 && first <= 256 && second < 5) {
            return true;
        }
        if (second >= 5 && second <= 256 && first < 5) {
            return false;
        }
        if (first >= 5 && first <= 256 && second > 256) {
            return true;
        }
        if (second >= 5 && second <= 256 && first > 256) {
            return false;
        }
        return first < second;
    }

    private static float value(float[] out, boolean channelsFirst, int channels, int anchors,
                               int anchor, int channel) {
        int idx = channelsFirst ? channel * anchors + anchor : anchor * channels + channel;
        return out[idx];
    }

    /** True when all four box channels stay within 0..2, i.e. the export normalized them. */
    private static boolean normalizedBoxes(float[] out, boolean channelsFirst,
                                           int channels, int anchors) {
        float max = 0f;
        for (int c = 0; c < 4; c++) {
            for (int a = 0; a < anchors; a++) {
                float v = Math.abs(value(out, channelsFirst, channels, anchors, a, c));
                if (v > max) {
                    max = v;
                }
            }
        }
        return max <= 2f;
    }

    /**
     * Maps one letterboxed-input box ({@code cx,cy,w,h} in tensor pixels) back to the
     * original frame as normalized 0..1 coordinates, clamped to the frame. Returns null
     * when the box degenerates (zero area) after clamping.
     */
    public static double[] mapBox(float cx, float cy, float w, float h,
                                  YoloPreprocessor.Letterbox lb,
                                  int origWidth, int origHeight) {
        float x1 = lb.fromTensorX(cx - w / 2f);
        float x2 = lb.fromTensorX(cx + w / 2f);
        float y1 = lb.fromTensorY(cy - h / 2f);
        float y2 = lb.fromTensorY(cy + h / 2f);
        double nx1 = clamp01(x1 / origWidth);
        double nx2 = clamp01(x2 / origWidth);
        double ny1 = clamp01(y1 / origHeight);
        double ny2 = clamp01(y2 / origHeight);
        // Keep the pair ordered after clamping (an off-frame box can invert).
        double left = Math.min(nx1, nx2);
        double right = Math.max(nx1, nx2);
        double top = Math.min(ny1, ny2);
        double bottom = Math.max(ny1, ny2);
        if (right - left <= 0d || bottom - top <= 0d) {
            return null;
        }
        return new double[] {left, top, right, bottom};
    }

    private static double clamp01(double v) {
        if (v < 0d) {
            return 0d;
        }
        if (v > 1d) {
            return 1d;
        }
        return v;
    }

    /** Intersection-over-union of two normalized boxes; 0 when they do not overlap. */
    public static float iou(Candidate a, Candidate b) {
        double ix1 = Math.max(a.x1(), b.x1());
        double iy1 = Math.max(a.y1(), b.y1());
        double ix2 = Math.min(a.x2(), b.x2());
        double iy2 = Math.min(a.y2(), b.y2());
        double iw = ix2 - ix1;
        double ih = iy2 - iy1;
        if (iw <= 0d || ih <= 0d) {
            return 0f;
        }
        double inter = iw * ih;
        double union = a.area() + b.area() - inter;
        if (union <= 0d) {
            return 0f;
        }
        return (float) (inter / union);
    }

    /**
     * Class-aware NMS: within a class, sort by confidence and drop every later box whose
     * IoU with a kept box is <em>greater than</em> {@code iouThreshold}. Boxes of different
     * classes never suppress each other (Ultralytics default, agnostic=False).
     */
    public static List<Candidate> nms(List<Candidate> candidates, float iouThreshold) {
        if (!Float.isFinite(iouThreshold) || iouThreshold < 0f || iouThreshold > 1f) {
            throw new IllegalArgumentException("NMS IoU threshold must be in [0,1]");
        }
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(Candidate::confidence).reversed());
        List<Candidate> kept = new ArrayList<>();
        for (Candidate candidate : sorted) {
            boolean suppressed = false;
            for (Candidate winner : kept) {
                if (winner.classId() == candidate.classId()
                        && iou(winner, candidate) > iouThreshold) {
                    suppressed = true;
                    break;
                }
            }
            if (!suppressed) {
                kept.add(candidate);
            }
        }
        return kept;
    }
}
