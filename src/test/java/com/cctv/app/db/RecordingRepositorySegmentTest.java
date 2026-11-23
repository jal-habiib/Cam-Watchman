package com.cctv.app.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the split the engine drives: {@code SegmentStarted} writes the row before the file is closed,
 * {@code ClipFinalized} turns it into a ready clip. A crash between the two leaves a {@code writing} row
 * that startup reconciliation handles (see {@link RetentionServiceTest}).
 */
class RecordingRepositorySegmentTest {

    @TempDir
    Path tmp;

    private Path dbFile;
    private RecordingRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = tmp.resolve("cctv.db");
        repository = RecordingRepository.start(dbFile);
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close();
        }
    }

    @Test
    void startedRowIsWritingThenFinalizedBecomesReady() throws Exception {
        Path file = tmp.resolve("camera1-1700000000000.mkv");
        Files.writeString(file, "mkv");

        repository.segmentStarted(1, 1_700_000_000_000L, file);
        assertTrue(repository.flush(Duration.ofSeconds(5)));

        Row writing = row();
        assertEquals("writing", writing.status());
        assertEquals(file.toAbsolutePath().toString(), writing.filePath());
        assertEquals(0L, writing.endUtc(), "end_utc is unknown until finalize");
        assertEquals("none", writing.triggerType());

        repository.segmentFinalized(1, 1_700_000_000_000L, 1_700_000_015_000L, 4L, file, true);
        assertTrue(repository.flush(Duration.ofSeconds(5)));

        Row ready = row();
        assertEquals("ready", ready.status());
        assertEquals(1_700_000_015_000L, ready.endUtc());
        assertEquals(4L, ready.sizeBytes());
        assertEquals("detection", ready.triggerType());
    }

    @Test
    void finalizedWithoutStartedInsertsAReadyRow() throws Exception {
        Path file = tmp.resolve("late.mkv");
        Files.writeString(file, "mkv");

        repository.segmentFinalized(2, 1_700_000_100_000L, 1_700_000_200_000L, 12L, file, false);
        assertTrue(repository.flush(Duration.ofSeconds(5)));

        Row row = row();
        assertEquals("ready", row.status());
        assertEquals("none", row.triggerType());
        assertEquals(12L, row.sizeBytes());
    }

    @Test
    void alertLinksToTheSegmentRowByCameraAndStart() throws Exception {
        Path clip = tmp.resolve("camera2-1700000000000.mkv");
        Path png = tmp.resolve("alert.png");
        Files.writeString(clip, "mkv");
        Files.writeString(png, "png");

        repository.segmentFinalized(2, 1_700_000_000_000L, 1_700_000_015_000L, 3L, clip, true);
        repository.alert(2, 7, 1_700_000_000_000L, png, 1_700_000_010_000L);
        assertTrue(repository.flush(Duration.ofSeconds(5)));

        try (Connection c = Database.open(dbFile); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT recording_id, snapshot_path FROM alerts")) {
            assertTrue(rs.next());
            long recordingId = rs.getLong(1);
            assertTrue(recordingId > 0, "alert should resolve the recording row");
            assertEquals(png.toAbsolutePath().toString(), rs.getString(2));
        }
    }

    @Test
    void alertWithoutSegmentKeepsRecordingIdNull() throws Exception {
        Path png = tmp.resolve("orphan-alert.png");
        Files.writeString(png, "png");

        repository.alert(9, 3, 0L, png, 1_700_000_010_000L);
        assertTrue(repository.flush(Duration.ofSeconds(5)));

        try (Connection c = Database.open(dbFile); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT recording_id FROM alerts")) {
            assertTrue(rs.next());
            rs.getLong(1);
            assertTrue(rs.wasNull(), "no segment start means no recording link");
        }
    }

    @Test
    void closeDrainsEveryAcceptedCommandEvenWhenQueueIsPressured() throws Exception {
        int submitted = 3_000;
        long base = 1_700_001_000_000L;
        for (int i = 0; i < submitted; i++) {
            long start = base + i * 20_000L;
            repository.segmentFinalized(1, start, start + 15_000L, 1L,
                    tmp.resolve("clip-" + i + ".mkv"), false);
        }
        long dropped = repository.droppedCommandCount();
        assertTrue(dropped < submitted, "the writer should accept at least some segment metadata");

        repository.close();

        assertEquals(0, repository.queueDepth(), "shutdown must drain the accepted queue");
        assertTrue(repository.failure().isEmpty(), "the database writer should finish without a fatal error");
        try (Connection c = Database.open(dbFile); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM recordings")) {
            assertTrue(rs.next());
            assertEquals(submitted - dropped, rs.getLong(1),
                    "every command accepted by the bounded queue must be committed before close returns");
        }
    }

    @Test
    void commandsAfterCloseAreCountedAsRejected() {
        repository.close();
        long before = repository.droppedCommandCount();

        repository.segmentStarted(1, 1_700_002_000_000L, tmp.resolve("after-close.mkv"));

        assertEquals(before + 1, repository.droppedCommandCount());
    }

    private record Row(String filePath, long endUtc, long sizeBytes, String triggerType, String status) {
    }

    private Row row() throws Exception {
        try (Connection c = Database.open(dbFile); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT file_path, COALESCE(end_utc,0), COALESCE(size_bytes,0), trigger_type, status"
                                + " FROM recordings ORDER BY id DESC LIMIT 1")) {
            assertTrue(rs.next(), "expected at least one recording row");
            return new Row(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5));
        }
    }
}
