package com.cctv.app.engine;

import com.cctv.app.event.SimpleEventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EngineHandleTest {

    @TempDir
    Path tempDir;

    @Test
    void recordOnlyCameraDoesNotLoadOrRequireAnAiModel() throws Exception {
        SimpleEventBus bus = new SimpleEventBus();
        EngineHandle handle = EngineHandle.create(3,
                URI.create("rtsp://127.0.0.1:8554/cam3_main"), null,
                tempDir, 10, true, false, bus, 1.0, null, null);

        assertTrue(handle.isRecording());
        assertEquals(1, bus.listenerCount());
        assertDoesNotThrow(handle::close);
        assertEquals(0, bus.listenerCount());
    }

    @Test
    void aiOnlyCameraUsesAnalysisPipelineWithoutRecording() throws Exception {
        Path model = Path.of("models", "yolo11n.onnx");
        assumeTrue(Files.isRegularFile(model), "bundled model is required for this integration test");
        SimpleEventBus bus = new SimpleEventBus();
        EngineHandle handle = EngineHandle.create(2,
                URI.create("rtsp://127.0.0.1:8554/cam2_main"), model,
                tempDir, 10, false, true, bus, 1.0, null, null);

        assertFalse(handle.isRecording());
        // The analysis worker publishes events; it does not subscribe to the event bus.
        assertEquals(0, bus.listenerCount());
        assertDoesNotThrow(handle::close);
        assertEquals(0, bus.listenerCount());
    }

    @Test
    void cameraWithNeitherRecordingNorAiDoesNotCreateAnEngine() {
        SimpleEventBus bus = new SimpleEventBus();
        assertThrows(IllegalArgumentException.class, () -> EngineHandle.create(4,
                URI.create("rtsp://127.0.0.1:8554/cam4_main"), null,
                tempDir, 10, false, false, bus, 1.0, null, null));
        assertEquals(0, bus.listenerCount());
    }
}
