package com.cctv.app.db;

import com.cctv.app.config.AppConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetentionServiceTest {

    private static final long DAY = 86_400_000L;
    private static final long NOW = 1_700_000_000_000L;

    @TempDir
    Path tmp;

    private Path recordings;
    private Path dbFile;
    private RecordingRepository repository;
    private long free = Long.MAX_VALUE;

    @BeforeEach
    void setUp() throws Exception {
        recordings = Files.createDirectories(tmp.resolve("recordings"));
        dbFile = tmp.resolve("cctv.db");
        repository = RecordingRepository.start(dbFile);
    }

    @AfterEach
    void tearDown() {
        repository.close();
    }

    private RetentionService.Policy policy(long maxBytes, long minFree) {
        return new RetentionService.Policy(7, maxBytes, minFree, 30_000L);
    }

    private RetentionService service(RetentionService.Policy policy, long now) {
        return new RetentionService(dbFile, recordings, policy, () -> now, () -> free);
    }

    private Path segment(long cameraId, long startUtc, int size) throws Exception {
        Path file = recordings.resolve("cam" + cameraId + "-" + startUtc + ".mkv");
        Files.write(file, new byte[size]);
        return file;
    }

    private void keep(long cameraId, long startUtc, int size) throws Exception {
        Path file = segment(cameraId, startUtc, size);
        repository.segmentStarted(cameraId, startUtc, file);
        repository.segmentFinalized(cameraId, startUtc, startUtc + 15_000, size, file, true);
        assertTrue(repository.flush(Duration.ofSeconds(5)));
    }

    private void drop(long cameraId, long startUtc, int size) throws Exception {
        Path file = segment(cameraId, startUtc, size);
        repository.segmentStarted(cameraId, startUtc, file);
        repository.segmentFinalized(cameraId, startUtc, startUtc + 15_000, size, file, false);
        assertTrue(repository.flush(Duration.ofSeconds(5)));
    }

    private long queryLong(String sql, Object... args) throws Exception {
        try (Connection c = Database.open(dbFile); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private String queryString(String sql, Object... args) throws Exception {
        try (Connection c = Database.open(dbFile); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Test
    void segmentLifecycleIsRecordedAsReadyDetection() throws Exception {
        keep(1, NOW, 120);

        assertEquals("ready", queryString("SELECT status FROM recordings"));
        assertEquals("detection", queryString("SELECT trigger_type FROM recordings"));
        assertEquals(120, queryLong("SELECT size_bytes FROM recordings"));
        assertEquals(NOW, queryLong("SELECT start_utc FROM recordings"));
    }

    @Test
    void finalizeWithoutPriorStartStillCreatesTheRow() throws Exception {
        Path file = segment(1, NOW, 64);
        repository.segmentFinalized(1, NOW, NOW + 15_000, 64, file, true);
        assertTrue(repository.flush(Duration.ofSeconds(5)));

        assertEquals(1, queryLong("SELECT COUNT(*) FROM recordings"));
        assertEquals("ready", queryString("SELECT status FROM recordings"));
    }

    @Test
    void unkeptSegmentIsDeletedAfterItsGracePeriod() throws Exception {
        drop(2, NOW, 50);
        String path = queryString("SELECT file_path FROM recordings");

        RetentionService.Report before = service(policy(1_000, 0), NOW + 1_000).runOnce();
        assertEquals(0, before.deleted());

        RetentionService.Report after = service(policy(1_000, 0), NOW + 46_000).runOnce();
        assertEquals(1, after.deleted());
        assertEquals(0, queryLong("SELECT COUNT(*) FROM recordings"));
        assertFalse(Files.exists(Path.of(path)));
    }

    @Test
    void detectedSegmentsSurviveUntilTheRetentionWindowEnds() throws Exception {
        keep(1, NOW, 50);

        RetentionService.Report inside = service(policy(1_000_000, 0), NOW + 6 * DAY).runOnce();
        assertEquals(0, inside.deleted());
        assertEquals(1, queryLong("SELECT COUNT(*) FROM recordings"));

        RetentionService.Report expired = service(policy(1_000_000, 0), NOW + 8 * DAY).runOnce();
        assertEquals(1, expired.deleted());
        assertEquals(0, queryLong("SELECT COUNT(*) FROM recordings"));
    }

    @Test
    void totalSizeCapDeletesOldestClipsFirst() throws Exception {
        keep(1, NOW, 100);
        keep(1, NOW + 15_000, 100);
        keep(1, NOW + 30_000, 100);

        RetentionService.Report report = service(policy(250, 0), NOW + 60_000).runOnce();

        assertEquals(1, report.deleted());
        assertEquals(2, queryLong("SELECT COUNT(*) FROM recordings"));
        assertEquals(NOW + 15_000, queryLong("SELECT MIN(start_utc) FROM recordings"));
        assertTrue(report.freedBytes() >= 100);
    }

    @Test
    void freeSpaceGuardDeletesEverythingAndPausesRecording() throws Exception {
        keep(1, NOW, 100);
        free = 0;

        RetentionService.Report report = service(policy(1_000_000, 10_000), NOW + 60_000).runOnce();

        assertEquals(1, report.deleted());
        assertTrue(report.recordingPaused());
        assertFalse(service(policy(1_000_000, 10_000), NOW).recordingAllowed());
    }

    @Test
    void failedFileDeleteLeavesTheRowForRetry() throws Exception {
        Path blocked = recordings.resolve("blocked.mkv");
        Files.createDirectories(blocked);
        Files.write(blocked.resolve("child"), new byte[]{1}); // non-empty dir cannot be deleted
        repository.segmentStarted(1, NOW, blocked);
        repository.segmentFinalized(1, NOW, NOW + 15_000, 1, blocked, false);
        assertTrue(repository.flush(Duration.ofSeconds(5)));

        RetentionService.Report report = service(policy(1_000_000, 0), NOW + 60_000).runOnce();

        assertEquals(0, report.deleted());
        assertEquals("ready", queryString("SELECT status FROM recordings"));
    }

    @Test
    void startupReconciliationRepairsRowsAndDeletesOrphans() throws Exception {
        keep(1, NOW, 40);                       // healthy row, must stay
        Path gone = recordings.resolve("gone.mkv");
        Files.write(gone, new byte[10]);
        repository.segmentStarted(2, NOW + 1_000, gone);
        assertTrue(repository.flush(Duration.ofSeconds(5)));
        Files.delete(gone);                     // file vanished while the row stayed

        Path written = recordings.resolve("writing.mkv");
        Files.write(written, new byte[33]);
        repository.segmentStarted(3, NOW + 2_000, written); // engine crashed before finalizing
        assertTrue(repository.flush(Duration.ofSeconds(5)));
        // force the row back to 'writing' as a crash would leave it
        try (Connection c = Database.open(dbFile); PreparedStatement ps = c.prepareStatement(
                "UPDATE recordings SET status='writing' WHERE camera_id=3")) {
            ps.executeUpdate();
        }
        Path orphan = recordings.resolve("orphan.mkv");
        Files.write(orphan, new byte[12]);
        Files.setLastModifiedTime(orphan, java.nio.file.attribute.FileTime.fromMillis(NOW - 60_000));

        RetentionService.ReconcileReport report =
                service(policy(1_000_000, 0), NOW + 120_000).reconcileStartup();

        assertEquals(1, report.rowsFinalized());
        assertEquals(1, report.rowsDropped());
        assertEquals(1, report.orphanFilesDeleted());
        assertEquals(2, queryLong("SELECT COUNT(*) FROM recordings"));
        assertEquals("ready", queryString("SELECT status FROM recordings WHERE camera_id=3"));
        assertEquals(33, queryLong("SELECT size_bytes FROM recordings WHERE camera_id=3"));
        assertFalse(Files.exists(orphan));
    }

    @Test
    void deletesTheJsonSidecarTogetherWithItsClip() throws Exception {
        long start = NOW - 8 * DAY;
        Path file = recordings.resolve("cam1-sidecar.mkv");
        Path sidecar = recordings.resolve("cam1-sidecar.mkv.json");
        Files.write(file, new byte[10]);
        Files.writeString(sidecar, "{\"schemaVersion\":1}");

        repository.segmentStarted(1, start, file);
        repository.segmentFinalized(1, start, start + 15_000, 10, file, true);
        assertTrue(repository.flush(Duration.ofSeconds(5)));

        RetentionService.Report report = service(policy(1_000_000, 0), NOW).runOnce();

        assertEquals(1, report.deleted());
        assertFalse(Files.exists(file), "clip removed");
        assertFalse(Files.exists(sidecar), "sidecar removed with the clip");
    }

    @Test
    void policyComesFromConfigDefaults() throws Exception {
        List<com.cctv.app.config.CameraConfig> cameras = List.of(new com.cctv.app.config.CameraConfig(
                1, "cam", java.net.URI.create("rtsp://127.0.0.1:8554/cam1_main"),
                java.net.URI.create("rtsp://127.0.0.1:8554/cam1_sub"), true, true));
        AppConfig config = new AppConfig(tmp.resolve("cameras.properties"), tmp.resolve("data"), cameras,
                15, 7, 40L * 1024 * 1024 * 1024, 10L * 1024 * 1024 * 1024, 1.0);
        RetentionService.Policy policy = RetentionService.Policy.fromConfig(config);

        assertEquals(7, policy.retentionDays());
        assertEquals(40L * 1024 * 1024 * 1024, policy.maxTotalBytes());
        assertEquals(10L * 1024 * 1024 * 1024, policy.minFreeBytes());
    }
}
