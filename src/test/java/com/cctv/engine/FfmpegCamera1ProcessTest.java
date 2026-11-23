package com.cctv.engine;

import com.cctv.app.event.SimpleEventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FfmpegCamera1ProcessTest {

    @TempDir
    Path tempDir;

    @Test
    void previewDecodeRemainsSmoothWhenAiInferenceRunsAtALowerRate() {
        assertEquals(10.0, FfmpegCamera1Process.previewFrameRate(1.0));
        assertEquals(10.0, FfmpegCamera1Process.previewFrameRate(4.0));
        assertEquals(15.0, FfmpegCamera1Process.previewFrameRate(15.0));
    }

    @Test
    void recordOnlyPipelineDoesNotRequireOrLoadAnAiModel() throws Exception {
        SimpleEventBus bus = new SimpleEventBus();
        FfmpegCamera1Pipeline pipeline = FfmpegCamera1Pipeline.create(3,
                URI.create("rtsp://127.0.0.1:8554/cam3_main"), null,
                tempDir, 10, bus, 1.0, camera -> java.util.List.of(),
                camera -> java.util.List.of(), false);

        assertEquals(1, bus.listenerCount());
        assertDoesNotThrow(pipeline::close);
        assertEquals(0, bus.listenerCount());
    }

    @Test
    void acceptsDirectCameraUrlWithCredentials() {
        SimpleEventBus bus = new SimpleEventBus();

        FfmpegCamera1Process source = new FfmpegCamera1Process(1,
                "rtsp://camera-user:secret@192.168.1.50:554/stream1",
                tempDir, 10, 1.0, bus);

        assertEquals(1, bus.listenerCount());
        assertDoesNotThrow(source::close);
        assertEquals(0, bus.listenerCount());
    }

    @Test
    void acceptsRemoteCameraUrl() {
        SimpleEventBus bus = new SimpleEventBus();

        FfmpegCamera1Process source = new FfmpegCamera1Process(1,
                "rtsp://203.0.113.20:554/stream", tempDir, 10, 1.0, bus);

        assertEquals(1, bus.listenerCount());
        assertDoesNotThrow(source::close);
    }

    @Test
    void rejectsNonRtspAndHostlessUrls() {
        SimpleEventBus bus = new SimpleEventBus();

        assertThrows(IllegalArgumentException.class, () ->
                new FfmpegCamera1Process(1, "http://192.168.1.50:554/stream",
                        tempDir, 10, 1.0, bus));
        assertThrows(IllegalArgumentException.class, () ->
                new FfmpegCamera1Process(1, "rtsp:///stream", tempDir, 10, 1.0, bus));

        assertEquals(0, bus.listenerCount());
    }

    @Test
    void acceptsCredentialFreeLoopbackRelayAndUnregistersOnClose() {
        SimpleEventBus bus = new SimpleEventBus();
        FfmpegCamera1Process source = new FfmpegCamera1Process(1,
                "rtsp://127.0.0.1:8554/cam1_main", tempDir, 10, 1.0, bus);

        assertEquals(1, bus.listenerCount());
        assertDoesNotThrow(source::close);
        assertEquals(0, bus.listenerCount());
    }
}
