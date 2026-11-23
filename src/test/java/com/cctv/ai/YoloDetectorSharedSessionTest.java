package com.cctv.ai;

import ai.onnxruntime.OrtSession;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class YoloDetectorSharedSessionTest {

    @Test
    void camerasShareOneSessionAndClosingOneCameraDoesNotCloseTheOthers() throws Exception {
        Path model = Path.of("models", "yolo11n.onnx");
        assumeTrue(Files.isRegularFile(model), "bundled model is required for this integration test");

        List<YoloDetector> detectors = new ArrayList<>();
        try {
            for (int camera = 0; camera < 4; camera++) {
                detectors.add(YoloDetector.load(model, 2,
                        YoloPostprocessor.DEFAULT_CONF_THRESHOLD,
                        YoloPostprocessor.DEFAULT_NMS_IOU));
            }

            OrtSession sharedSession = detectors.get(0).session();
            for (YoloDetector detector : detectors) {
                assertSame(sharedSession, detector.session(),
                        "all cameras using the same model/settings must share one ONNX session");
            }

            YoloDetector.resetPeakInferenceCountForTest();
            ExecutorService workers = Executors.newFixedThreadPool(4);
            try {
                List<Future<YoloDetector.Inference>> inferences = new ArrayList<>();
                for (int camera = 0; camera < 4; camera++) {
                    final int cameraId = camera + 1;
                    final YoloDetector detector = detectors.get(camera);
                    inferences.add(workers.submit(() -> detector.detectTimed(
                            new BufferedImage(640, 360, BufferedImage.TYPE_3BYTE_BGR),
                            cameraId, System.currentTimeMillis())));
                }
                for (Future<YoloDetector.Inference> inference : inferences) {
                    assertEquals(320, inference.get().inputWidth());
                }
                int peak = YoloDetector.peakInferenceCountForTest();
                assertTrue(peak >= 1, "at least one inference should have executed");
                assertTrue(peak <= 2, "the shared inference gate must cap simultaneous ONNX runs at two");
            } finally {
                workers.shutdownNow();
            }

            detectors.get(0).close();
            assertDoesNotThrow(sharedSession::getInputInfo,
                    "closing one camera must not close the shared session for other cameras");
        } finally {
            detectors.forEach(YoloDetector::close);
        }
    }
}
