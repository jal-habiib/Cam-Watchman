package com.cctv.app.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @TempDir
    Path tmp;

    private static final String VALID = """
            # comment
            cameras.1.id=1
            cameras.1.name=Front gate
            cameras.1.url=rtsp://user:secret@203.0.113.64:554/Streaming/Channels/101
            cameras.1.substream_url=rtsp://user:secret@203.0.113.64:554/Streaming/Channels/102
            cameras.1.record=true
            cameras.1.ai=true

            cameras.2.id=2
            cameras.2.name=Corridor
            cameras.2.substream_url=rtsp://203.0.113.65:554/sub
            cameras.2.record=false
            cameras.2.ai=false
            """;

    private AppConfig parse(String text) throws IOException {
        Path file = tmp.resolve("cameras.properties");
        Files.writeString(file, text);
        return ConfigLoader.load(file, tmp.resolve("default-data"));
    }

    @Test
    void loadsValidFileWithDefaults() throws IOException {
        AppConfig cfg = parse(VALID);

        assertEquals(2, cfg.cameras().size());
        assertEquals(1, cfg.cameras().get(0).id());
        assertEquals("Front gate", cfg.cameras().get(0).name());
        assertTrue(cfg.cameras().get(0).needsMainStream());
        assertFalse(cfg.cameras().get(1).needsMainStream());
        assertEquals("rtsp://203.0.113.65:554/sub", cfg.cameras().get(1).displayUrl().toString());

        assertEquals(ConfigLoader.DEFAULT_SEGMENT_SECONDS, cfg.segmentSeconds());
        assertEquals(ConfigLoader.DEFAULT_RETENTION_DAYS, cfg.retentionDays());
        assertEquals(40L * 1024 * 1024 * 1024, cfg.retentionMaxBytes());
        assertEquals(10L * 1024 * 1024 * 1024, cfg.minFreeBytes());
        assertEquals(1.0, cfg.aiRateFps());

        assertEquals(tmp.resolve("default-data"), cfg.baseDataDir());
        assertEquals(tmp.resolve("default-data").resolve("recordings"), cfg.recordingsDir());
        assertEquals(tmp.resolve("default-data").resolve("cam-watchman.db"), cfg.databaseFile());
        assertSame(cfg.cameras().get(0), cfg.fullPipelineCamera());
    }

    @Test
    void readsOverridesAndRelativeBaseDirAgainstConfigFile() throws IOException {
        AppConfig cfg = parse(VALID + """
                base.data.dir=../runtime
                recording.segment.seconds=20
                retention.days=3
                retention.size.gb=5
                retention.min.free.gb=2
                ai.rate.fps=2.5
                """);

        assertEquals(tmp.resolve("../runtime").toAbsolutePath().normalize(), cfg.baseDataDir());
        assertEquals(20, cfg.segmentSeconds());
        assertEquals(3, cfg.retentionDays());
        assertEquals(5L * 1024 * 1024 * 1024, cfg.retentionMaxBytes());
        assertEquals(2L * 1024 * 1024 * 1024, cfg.minFreeBytes());
        assertEquals(2.5, cfg.aiRateFps());
    }

    @Test
    void rejectsDuplicateIds() {
        String text = VALID + """
                cameras.3.id=1
                cameras.3.name=Duplicate
                cameras.3.substream_url=rtsp://203.0.113.9/sub
                """;
        ConfigException ex = assertThrows(ConfigException.class, () -> parse(text));
        assertTrue(ex.problems().stream().anyMatch(p -> p.contains("duplicate camera id 1")), ex.getMessage());
    }

    @Test
    void rejectsBadSchemeAndMissingHost() {
        String text = """
                cameras.1.name=Bad
                cameras.1.url=http://example.com/stream
                cameras.2.name=NoHost
                cameras.2.substream_url=rtsp:///nohost
                """;
        ConfigException ex = assertThrows(ConfigException.class, () -> parse(text));
        assertTrue(ex.problems().stream().anyMatch(p -> p.contains("unsupported scheme 'http'")), ex.getMessage());
        assertTrue(ex.problems().stream().anyMatch(p -> p.contains("URL has no host")), ex.getMessage());
    }

    @Test
    void rejectsAiCameraWithoutMainStream() {
        String text = """
                cameras.1.name=AI on substream only
                cameras.1.substream_url=rtsp://203.0.113.5/sub
                cameras.1.ai=true
                """;
        ConfigException ex = assertThrows(ConfigException.class, () -> parse(text));
        assertTrue(ex.problems().stream().anyMatch(p -> p.contains("no main-stream url")), ex.getMessage());
    }

    @Test
    void emptyFileMeansFirstRun() throws IOException {
        AppConfig cfg = parse("# nothing here\n");
        assertTrue(cfg.cameras().isEmpty(), "a file with no cameras is the first-run state");
        assertEquals(ConfigLoader.DEFAULT_SEGMENT_SECONDS, cfg.segmentSeconds());
    }

    @Test
    void emptyConfigBuildsDefaults() {
        AppConfig cfg = ConfigLoader.emptyConfig(tmp.resolve("cameras.properties"), tmp);
        assertTrue(cfg.cameras().isEmpty());
        assertTrue(cfg.baseDataDir().isAbsolute());
        assertEquals(ConfigLoader.DEFAULT_SEGMENT_SECONDS, cfg.segmentSeconds());
        assertEquals(ConfigLoader.DEFAULT_RETENTION_DAYS, cfg.retentionDays());
    }

    @Test
    void rejectsOutOfRangeValues() {
        String text = VALID + """
                recording.segment.seconds=90
                ai.rate.fps=0
                """;
        ConfigException ex = assertThrows(ConfigException.class, () -> parse(text));
        assertTrue(ex.problems().stream().anyMatch(p -> p.contains("recording.segment.seconds")), ex.getMessage());
        assertTrue(ex.problems().stream().anyMatch(p -> p.contains("ai.rate.fps")), ex.getMessage());
    }

    @Test
    void missingFileIsAConfigException() {
        ConfigException ex = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(tmp.resolve("nope.properties"), tmp));
        assertTrue(ex.getMessage().contains("cannot read"), ex.getMessage());
    }

    @Test
    void redactionMasksCredentialsButKeepsHostAndPath() {
        assertEquals("rtsp://***@203.0.113.64:554/Streaming/Channels/101",
                Redaction.redact("rtsp://admin:hunter2@203.0.113.64:554/Streaming/Channels/101"));
        assertEquals("rtsp://***@cam.local/live",
                Redaction.redact("rtsp://user@cam.local/live"));
        assertEquals("https://example.com/x?password=***&y=2",
                Redaction.redact("https://example.com/x?password=abc123&y=2"));
        assertEquals("no credentials here", Redaction.redact("no credentials here"));
        assertNull(Redaction.redact(null));
    }

    @Test
    void cameraDefaultsAndDisplayUrlFallback() throws IOException {
        AppConfig cfg = parse("""
                cameras.1.name=Main only
                cameras.1.url=rtsp://203.0.113.2/main
                """);
        CameraConfig cam = cfg.cameras().get(0);
        assertTrue(cam.hasMain());
        assertFalse(cam.hasSubstream());
        assertEquals(cam.mainUrl(), cam.displayUrl());
        assertFalse(cam.needsMainStream(), "display-only unless record/ai enabled");
        assertTrue(cfg.baseDataDir().isAbsolute());
        assertTrue(ConfigLoader.defaultBaseDataDir().isAbsolute());
        assertTrue(Map.of("k", "v").containsKey("k"));
        List<String> none = List.of();
        assertTrue(none.isEmpty());
    }
}

