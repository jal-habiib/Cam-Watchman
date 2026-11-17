package com.cctv.ai;

import com.cctv.contract.Detection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Box mapping, confidence/class filtering and NMS (spike 0c / item 2c), no model involved. */
class YoloPostprocessorTest {

    private static final float EPS = 1e-4f;

    // 640x480 frame letterboxed into a 320x320 model: scale 0.5, padY 40, no padX.
    private static final YoloPreprocessor.Letterbox LB =
            YoloPreprocessor.letterbox(640, 480, 320, 320);

    @Test
    void mapBoxUndoesLetterboxAndNormalizes() {
        // Original pixel box (100,100)-(200,300) -> tensor (50,90)-(100,190).
        float cx = 75f;
        float cy = 140f;
        float w = 50f;
        float h = 100f;
        double[] box = YoloPostprocessor.mapBox(cx, cy, w, h, LB, 640, 480);
        assertEquals(100.0 / 640.0, box[0], EPS);
        assertEquals(100.0 / 480.0, box[1], EPS);
        assertEquals(200.0 / 640.0, box[2], EPS);
        assertEquals(300.0 / 480.0, box[3], EPS);
    }

    @Test
    void centreBoxStaysCentred() {
        // Tensor centre -> frame centre, despite the vertical pad.
        double[] box = YoloPostprocessor.mapBox(160f, 160f, 32f, 64f, LB, 640, 480);
        assertEquals(0.5, (box[0] + box[2]) / 2, EPS);
        assertEquals(0.5, (box[1] + box[3]) / 2, EPS);
        assertEquals(32.0 / 0.5 / 640.0 / 2, (box[2] - box[0]) / 2, EPS);
    }

    @Test
    void boxIsClampedToFrame() {
        // Tensor box starting left of the frame: x1 clamps to 0, width shrinks accordingly.
        double[] box = YoloPostprocessor.mapBox(10f, 160f, 80f, 40f, LB, 640, 480);
        assertEquals(0.0, box[0], EPS);
        assertTrue(box[2] > 0.0 && box[2] < 0.2);
    }

    @Test
    void boxFullyInsidePadIsDropped() {
        // 4:3 content occupies tensor y 40..280; a box at y=10 lives in the pad.
        assertNull(YoloPostprocessor.mapBox(160f, 10f, 8f, 4f, LB, 640, 480));
    }

    @Test
    void decodeReadsChannelsFirstOutput() {
        float[] out = output(1, 84, 2100, true);
        setChannelsFirst(out, 2100, 0, 0, 75f);    // cx
        setChannelsFirst(out, 2100, 0, 1, 140f);   // cy
        setChannelsFirst(out, 2100, 0, 2, 50f);    // w
        setChannelsFirst(out, 2100, 0, 3, 100f);   // h
        setChannelsFirst(out, 2100, 0, 4 + YoloPostprocessor.COCO_PERSON, 0.87f);

        List<Detection> detections = YoloPostprocessor.decode(out, new long[] {1, 84, 2100},
                LB, 640, 480, 7L, 1234L);

        assertEquals(1, detections.size());
        Detection d = detections.get(0);
        assertEquals("person", d.objectClass());
        assertEquals(0.87f, d.confidence(), EPS);
        assertEquals(7L, d.cameraId());
        assertEquals(1234L, d.frameUtcMs());
        assertEquals(100.0 / 640.0, d.x1(), EPS);
        assertEquals(100.0 / 480.0, d.y1(), EPS);
        assertEquals(200.0 / 640.0, d.x2(), EPS);
        assertEquals(300.0 / 480.0, d.y2(), EPS);
    }

    @Test
    void decodeReadsTransposedOutput() {
        float[] out = output(1, 2100, 84, false);
        setChannelsLast(out, 84, 0, 0, 75f);
        setChannelsLast(out, 84, 0, 1, 140f);
        setChannelsLast(out, 84, 0, 2, 50f);
        setChannelsLast(out, 84, 0, 3, 100f);
        setChannelsLast(out, 84, 0, 4 + YoloPostprocessor.COCO_PERSON, 0.9f);

        List<Detection> detections = YoloPostprocessor.decode(out, new long[] {1, 2100, 84},
                LB, 640, 480, 1L, 0L);
        assertEquals(1, detections.size());
        assertEquals("person", detections.get(0).objectClass());
        assertEquals(100.0 / 640.0, detections.get(0).x1(), EPS);
    }

    @Test
    void confidenceThresholdIsInclusiveAtHalf() {
        float[] out = output(1, 84, 2100, true);
        setChannelsFirst(out, 2100, 0, 0, 75f);
        setChannelsFirst(out, 2100, 0, 1, 140f);
        setChannelsFirst(out, 2100, 0, 2, 50f);
        setChannelsFirst(out, 2100, 0, 3, 100f);
        setChannelsFirst(out, 2100, 0, 4, 0.5f);
        assertEquals(1, YoloPostprocessor.decode(out, new long[] {1, 84, 2100}, LB, 640, 480,
                1L, 0L).size());

        // Same anchor just below 0.5 is dropped.
        setChannelsFirst(out, 2100, 0, 4, 0.4999f);
        assertTrue(YoloPostprocessor.decode(out, new long[] {1, 84, 2100}, LB, 640, 480,
                1L, 0L).isEmpty());
    }

    @Test
    void classFilterDropsBusAndBicycleKeepsCarTruckMotorcycle() {
        for (int classId : new int[] {YoloPostprocessor.COCO_BUS, YoloPostprocessor.COCO_BICYCLE}) {
            float[] out = output(1, 84, 1, true);
            setChannelsFirst(out, 1, 0, 0, 75f);
            setChannelsFirst(out, 1, 0, 1, 140f);
            setChannelsFirst(out, 1, 0, 2, 50f);
            setChannelsFirst(out, 1, 0, 3, 100f);
            setChannelsFirst(out, 1, 0, 4 + classId, 0.99f);
            assertTrue(YoloPostprocessor.decode(out, new long[] {1, 84, 1}, LB, 640, 480,
                    1L, 0L).isEmpty(), "class " + classId + " must be filtered");
        }
        for (int classId : new int[] {YoloPostprocessor.COCO_CAR, YoloPostprocessor.COCO_TRUCK,
                YoloPostprocessor.COCO_MOTORCYCLE}) {
            float[] out = output(1, 84, 1, true);
            setChannelsFirst(out, 1, 0, 0, 75f);
            setChannelsFirst(out, 1, 0, 1, 140f);
            setChannelsFirst(out, 1, 0, 2, 50f);
            setChannelsFirst(out, 1, 0, 3, 100f);
            setChannelsFirst(out, 1, 0, 4 + classId, 0.99f);
            List<Detection> d = YoloPostprocessor.decode(out, new long[] {1, 84, 1}, LB,
                    640, 480, 1L, 0L);
            assertEquals(1, d.size(), "class " + classId + " must be kept");
        }
    }

    @Test
    void decodeAcceptsNormalizedBoxesFromOtherExports() {
        // If an export emits 0..1 boxes, decode must scale them by the model input size.
        float[] out = output(1, 84, 1, true);
        setChannelsFirst(out, 1, 0, 0, 75f / 320f);
        setChannelsFirst(out, 1, 0, 1, 140f / 320f);
        setChannelsFirst(out, 1, 0, 2, 50f / 320f);
        setChannelsFirst(out, 1, 0, 3, 100f / 320f);
        setChannelsFirst(out, 1, 0, 4, 0.8f);
        List<Detection> d = YoloPostprocessor.decode(out, new long[] {1, 84, 1}, LB, 640, 480,
                1L, 0L);
        assertEquals(1, d.size());
        assertEquals(100.0 / 640.0, d.get(0).x1(), EPS);
    }

    @Test
    void iouOfHalfOverlapIsOneThird() {
        YoloPostprocessor.Candidate a = person(0.9f, 0, 0, 0.5, 0.5);
        YoloPostprocessor.Candidate b = person(0.8f, 0.25, 0, 0.75, 0.5);
        assertEquals(1f / 3f, YoloPostprocessor.iou(a, b), EPS);
        assertEquals(0f, YoloPostprocessor.iou(a, person(0.8f, 0.6, 0.6, 0.9, 0.9)), EPS);
    }

    @Test
    void nmsKeepsHighestConfidenceOfOverlappingSameClassOnly() {
        YoloPostprocessor.Candidate best = person(0.90f, 0.10, 0.10, 0.50, 0.50);
        YoloPostprocessor.Candidate overlap = person(0.80f, 0.12, 0.12, 0.52, 0.52);
        YoloPostprocessor.Candidate sameBoxOtherClass = new YoloPostprocessor.Candidate(
                YoloPostprocessor.COCO_CAR, "car", 0.70f, 0.10, 0.10, 0.50, 0.50);
        YoloPostprocessor.Candidate far = person(0.60f, 0.70, 0.70, 0.90, 0.90);

        List<YoloPostprocessor.Candidate> kept = YoloPostprocessor.nms(
                List.of(overlap, sameBoxOtherClass, far, best), 0.45f);

        assertEquals(3, kept.size());
        assertTrue(kept.contains(best));
        assertTrue(kept.contains(sameBoxOtherClass), "class-aware NMS keeps the car");
        assertTrue(kept.contains(far));
        assertFalse(kept.contains(overlap));
    }

    @Test
    void nmsThresholdIsExclusive() {
        YoloPostprocessor.Candidate a = person(0.9f, 0, 0, 0.5, 0.5);
        YoloPostprocessor.Candidate b = person(0.8f, 0.25, 0, 0.75, 0.5);
        float iou = YoloPostprocessor.iou(a, b);
        // Suppression needs IoU strictly greater than the threshold.
        assertEquals(2, YoloPostprocessor.nms(List.of(a, b), iou).size());
        assertEquals(1, YoloPostprocessor.nms(List.of(a, b), iou - 0.001f).size());
    }

    @Test
    void nmsEmptyInput() {
        assertTrue(YoloPostprocessor.nms(List.of(), 0.45f).isEmpty());
    }

    @Test
    void rejectsMalformedShapes() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> YoloPostprocessor.decode(new float[12], new long[] {1, 3, 4},
                        LB, 640, 480, 1L, 0L));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> YoloPostprocessor.decode(new float[10], new long[] {5},
                        LB, 640, 480, 1L, 0L));
    }

    // ---- helpers ----------------------------------------------------------------

    private static float[] output(long batch, long channels, long anchors, boolean channelsFirst) {
        return new float[(int) (batch * channels * anchors)];
    }

    private static void setChannelsFirst(float[] out, int anchors, int anchor,
                                         int channel, float value) {
        out[channel * anchors + anchor] = value;
    }

    private static void setChannelsLast(float[] out, int channels, int anchor,
                                        int channel, float value) {
        out[anchor * channels + channel] = value;
    }

    private static YoloPostprocessor.Candidate person(float conf,
                                                      double x1, double y1,
                                                      double x2, double y2) {
        return new YoloPostprocessor.Candidate(YoloPostprocessor.COCO_PERSON, "person",
                conf, x1, y1, x2, y2);
    }
}
