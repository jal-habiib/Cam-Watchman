package com.cctv.engine;

import com.cctv.app.event.SimpleEventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FfmpegCamera1ProcessTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsCredentialBearingRtspUrlBeforeStartingAProcess() {
        SimpleEventBus bus = new SimpleEventBus();

        assertThrows(IllegalArgumentException.class, () ->
                new FfmpegCamera1Process(1,
                        "rtsp://camera-user:secret@127.0.0.1:8554/cam1",
                        tempDir, 10, 1.0, bus));

        assertEquals(0, bus.listenerCount());
    }

    @Test
    void rejectsRemoteRtspUrlAndQueryTokens() {
        SimpleEventBus bus = new SimpleEventBus();

        assertThrows(IllegalArgumentException.class, () ->
                new FfmpegCamera1Process(1, "rtsp://203.0.113.20:554/stream",
                        tempDir, 10, 1.0, bus));
        assertThrows(IllegalArgumentException.class, () ->
                new FfmpegCamera1Process(1, "rtsp://127.0.0.1:8554/cam1?token=secret",
                        tempDir, 10, 1.0, bus));

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

