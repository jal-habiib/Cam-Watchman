package com.cctv.app.db;

import com.cctv.app.config.AppConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Alert snapshot pruning shares the clip retention window (plan 3c/5d: no unbounded snapshot folder). */
class SnapshotRetentionTest {

    private static final long DAY = 86_400_000L;
    private static final long NOW = 1_700_000_000_000L;

    @TempDir
    Path tmp;

    private RetentionService.Policy policy() {
        return new RetentionService.Policy(7, 40L * 1024 * 1024 * 1024, 10L * 1024 * 1024 * 1024, 30_000L);
    }

    @Test
    void prunesSnapshotsOlderThanTheRetentionWindow() throws Exception {
        Path recordings = Files.createDirectories(tmp.resolve("recordings"));
        Path snapshots = Files.createDirectories(tmp.resolve("snapshots"));
        Path dbFile = tmp.resolve("cctv.db");
        Path old = Files.writeString(snapshots.resolve("alert-old.png"), "png");
        Path fresh = Files.writeString(snapshots.resolve("alert-new.png"), "png");
        Path other = Files.writeString(snapshots.resolve("notes.txt"), "keep me");
        Files.setLastModifiedTime(old, FileTime.from(NOW - 9 * DAY, TimeUnit.MILLISECONDS));
        Files.setLastModifiedTime(fresh, FileTime.from(NOW - 1 * DAY, TimeUnit.MILLISECONDS));

        RetentionService.Policy policy = policy();
        RetentionService service = new RetentionService(dbFile, recordings, snapshots, policy,
                () -> NOW, () -> Long.MAX_VALUE);

        RetentionService.Report report = service.runOnce();

        assertEquals(1, report.snapshotsDeleted());
        assertFalse(Files.exists(old), "snapshot past the 7 day window should be gone");
        assertTrue(Files.exists(fresh), "snapshot inside the window must survive");
        assertTrue(Files.exists(other), "non-snapshot files are never touched");
    }

    @Test
    void noSnapshotDirMeansNoPruningAndNoFailure() throws Exception {
        Path recordings = Files.createDirectories(tmp.resolve("recordings"));
        Path dbFile = tmp.resolve("cctv.db");
        RetentionService service = new RetentionService(dbFile, recordings, policy(),
                () -> NOW, () -> Long.MAX_VALUE);

        RetentionService.Report report = service.runOnce();

        assertEquals(0, report.snapshotsDeleted());
    }

    @Test
    void pruningASnapshotClearsTheAlertRowPathButKeepsTheRow() throws Exception {
        Path recordings = Files.createDirectories(tmp.resolve("recordings"));
        Path snapshots = Files.createDirectories(tmp.resolve("snapshots"));
        Path dbFile = tmp.resolve("cctv.db");
        Path old = Files.writeString(snapshots.resolve("alert-old.png"), "png");
        Files.setLastModifiedTime(old, FileTime.from(NOW - 9 * DAY, TimeUnit.MILLISECONDS));
        Path fresh = Files.writeString(snapshots.resolve("alert-new.png"), "png");
        Files.setLastModifiedTime(fresh, FileTime.from(NOW - 1 * DAY, TimeUnit.MILLISECONDS));

        try (var connection = Database.open(dbFile)) {
            try (var ps = connection.prepareStatement(
                    "INSERT INTO alerts(camera_id, zone_id, recording_id, snapshot_path, created_utc)"
                            + " VALUES (?,?,?,?,?)")) {
                ps.setLong(1, 1L);
                ps.setLong(2, 1L);
                ps.setNull(3, java.sql.Types.INTEGER);
                ps.setString(4, old.toAbsolutePath().toString());
                ps.setLong(5, NOW);
                ps.executeUpdate();
            }
            try (var ps = connection.prepareStatement(
                    "INSERT INTO alerts(camera_id, zone_id, recording_id, snapshot_path, created_utc)"
                            + " VALUES (?,?,?,?,?)")) {
                ps.setLong(1, 1L);
                ps.setLong(2, 1L);
                ps.setNull(3, java.sql.Types.INTEGER);
                ps.setString(4, fresh.toAbsolutePath().toString());
                ps.setLong(5, NOW - 1000);
                ps.executeUpdate();
            }
        }

        RetentionService service = new RetentionService(dbFile, recordings, snapshots, policy(),
                () -> NOW, () -> Long.MAX_VALUE);
        RetentionService.Report report = service.runOnce();

        assertEquals(1, report.snapshotsDeleted());
        try (var connection = Database.open(dbFile);
             var ps = connection.prepareStatement(
                     "SELECT COUNT(*), SUM(CASE WHEN snapshot_path IS NULL THEN 1 ELSE 0 END)"
                             + " FROM alerts");
             var rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1), "history rows must survive the snapshot prune");
            assertEquals(1, rs.getInt(2), "only the pruned snapshot path is cleared");
        }
    }
}
