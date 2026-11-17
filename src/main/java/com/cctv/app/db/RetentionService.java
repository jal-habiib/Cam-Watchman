package com.cctv.app.db;

import com.cctv.app.config.AppConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retention, disk guards and startup reconciliation (plan 3c, §Data and storage rules).
 *
 * <p>Deletion order per clip is fixed: mark the row {@code deleting}, delete the file, delete the
 * row. A file that cannot be deleted (still open, locked) leaves the row in {@code ready} and is
 * retried on the next pass. The segment currently being written is never in {@code ready}, so the
 * sweep cannot touch it.
 */
public final class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
    private static final long DAY_MS = 86_400_000L;
    private static final long DEFAULT_UNKEPT_GRACE_MS = 30_000L;

    public record Policy(int retentionDays, long maxTotalBytes, long minFreeBytes, long unkeptGraceMs) {

        public Policy {
            if (retentionDays < 1) {
                throw new IllegalArgumentException("retentionDays must be >= 1");
            }
            if (maxTotalBytes < 0 || minFreeBytes < 0 || unkeptGraceMs < 0) {
                throw new IllegalArgumentException("retention limits must not be negative");
            }
        }

        public static Policy fromConfig(AppConfig config) {
            return new Policy(config.retentionDays(), config.retentionMaxBytes(), config.minFreeBytes(),
                    DEFAULT_UNKEPT_GRACE_MS);
        }
    }

    /** Outcome of one sweep. */
    public record Report(int deleted, long freedBytes, boolean recordingPaused, List<Long> deletedIds,
                         int snapshotsDeleted) {
    }

    /** Outcome of the startup pass. */
    public record ReconcileReport(int rowsFinalized, int rowsDropped, int orphanFilesDeleted) {
    }

    private record Row(long id, String path, long startUtc, long endUtc, long size, String trigger) {
        long effectiveEnd() {
            return endUtc > 0 ? endUtc : startUtc;
        }
    }

    private final Path dbFile;
    private final Path recordingsDir;
    private final Policy policy;
    private final LongSupplier nowMs;
    private final LongSupplier freeBytes;
    private final Path snapshotsDir;

    public RetentionService(Path dbFile, Path recordingsDir, Policy policy, LongSupplier nowMs,
                            LongSupplier freeBytes) {
        this(dbFile, recordingsDir, null, policy, nowMs, freeBytes);
    }

    /**
     * @param snapshotsDir alert snapshot folder pruned with the same age window, or {@code null} when
     *                     the caller does not own alert files
     */
    public RetentionService(Path dbFile, Path recordingsDir, Path snapshotsDir, Policy policy,
                            LongSupplier nowMs, LongSupplier freeBytes) {
        this.dbFile = dbFile;
        this.recordingsDir = recordingsDir;
        this.snapshotsDir = snapshotsDir;
        this.policy = policy;
        this.nowMs = nowMs;
        this.freeBytes = freeBytes;
    }

    public Policy policy() {
        return policy;
    }

    /** Disk guard: false means "do not keep new clips until space is back" (plan 3c). */
    public boolean recordingAllowed() {
        return freeBytes.getAsLong() >= policy.minFreeBytes();
    }

    /**
     * Startup pass (plan 3c): rows without files are dropped, files without rows are deleted,
     * rows left in {@code writing}/{@code deleting} by a crash are repaired. Runs before any
     * camera starts, so nothing is being written.
     */
    public ReconcileReport reconcileStartup() {
        int finalized = 0;
        int dropped = 0;
        int orphans = 0;
        try (Connection connection = Database.open(dbFile)) {
            Set<String> referenced = new HashSet<>();
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, file_path, start_utc, COALESCE(end_utc,0), size_bytes, trigger_type, status "
                            + "FROM recordings");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                            rs.getLong(5), rs.getString(6)));
                    referenced.add(rs.getString(2));
                    if ("writing".equals(rs.getString(7))) {
                        Path file = Path.of(rs.getString(2));
                        long size = -1;
                        try {
                            if (Files.isRegularFile(file)) {
                                size = Files.size(file);
                            }
                        } catch (IOException e) {
                            log.warn("reconcile: cannot stat {}: {}", file, e.toString());
                        }
                        if (size >= 0) {
                            try (PreparedStatement up = connection.prepareStatement(
                                    "UPDATE recordings SET status='ready', size_bytes=? WHERE id=?")) {
                                up.setLong(1, size);
                                up.setLong(2, rs.getLong(1));
                                up.executeUpdate();
                                finalized++;
                            }
                        } else {
                            dropped += deleteRow(connection, rs.getLong(1));
                        }
                    } else if ("deleting".equals(rs.getString(7))) {
                        Path file = Path.of(rs.getString(2));
                        try {
                            deleteClip(file);
                            dropped += deleteRow(connection, rs.getLong(1));
                        } catch (IOException e) {
                            log.warn("reconcile: cannot delete {} yet: {}", file, e.toString());
                        }
                    }
                }
            }
            // Rows whose file vanished behind our back (manual cleanup, antivirus) must go too.
            for (Row row : rows) {
                if (!Files.exists(Path.of(row.path()))) {
                    dropped += deleteRow(connection, row.id());
                }
            }
            orphans = deleteOrphanFiles(connection, referenced);
        } catch (SQLException e) {
            log.error("startup reconciliation failed", e);
        }
        if (finalized + dropped + orphans > 0) {
            log.info("reconcile: {} rows finalized, {} rows dropped, {} orphan files deleted",
                    finalized, dropped, orphans);
        }
        return new ReconcileReport(finalized, dropped, orphans);
    }

    /**
     * One retention sweep: unkept segments past their grace, clips older than the retention window,
     * the total-size cap, then the free-space guard (which may pause recording).
     */
    public Report runOnce() {
        List<Long> deletedIds = new ArrayList<>();
        long freed = 0;
        boolean paused = false;
        try (Connection connection = Database.open(dbFile)) {
            long now = nowMs.getAsLong();
            List<Row> ready = loadReady(connection);
            LinkedHashSet<Row> doomed = new LinkedHashSet<>();
            long ageCut = now - policy.retentionDays() * DAY_MS;
            long graceCut = now - policy.unkeptGraceMs();

            for (Row row : ready) {
                if ("none".equals(row.trigger()) && row.effectiveEnd() <= graceCut) {
                    doomed.add(row);
                }
            }
            for (Row row : ready) {
                if (row.startUtc() < ageCut) {
                    doomed.add(row);
                }
            }
            long total = ready.stream().mapToLong(Row::size).sum();
            List<Row> byAge = ready.stream()
                    .sorted(Comparator.comparingLong(Row::startUtc))
                    .toList();
            for (Row row : byAge) {
                if (total <= policy.maxTotalBytes()) {
                    break;
                }
                doomed.add(row);
                total -= row.size();
            }

            long free = freeBytes.getAsLong();
            for (Row row : byAge) {
                if (free >= policy.minFreeBytes()) {
                    break;
                }
                if (!doomed.contains(row)) {
                    doomed.add(row);
                    free += row.size();
                }
            }
            if (free < policy.minFreeBytes()) {
                paused = true;
                log.warn("free space {} bytes below the {} byte guard - pausing new clips",
                        free, policy.minFreeBytes());
            }

            for (Row row : doomed) {
                try {
                    long size = row.size();
                    deleteClip(Path.of(row.path()));
                    deletedIds.add(row.id());
                    freed += size;
                    deleteRow(connection, row.id());
                } catch (IOException e) {
                    log.warn("retention: cannot delete {} ({}) - row left for retry", row.path(), e.toString());
                }
            }
        } catch (SQLException e) {
            log.error("retention sweep failed", e);
        }
        if (!deletedIds.isEmpty() || paused) {
            log.info("retention: deleted {} clips ({} bytes){}", deletedIds.size(), freed,
                    paused ? ", recording paused" : "");
        }
        int snapshots = pruneSnapshots();
        return new Report(deletedIds.size(), freed, paused, deletedIds, snapshots);
    }

    /**
     * Deletes a clip and its JSON sidecar (plan 4b export). The sidecar is derived data, so a failure
     * to delete it never blocks deleting the clip; it is retried by the orphan sweep as a stale file.
     */
    private static void deleteClip(Path file) throws IOException {
        Files.deleteIfExists(file);
        try {
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".json"));
        } catch (IOException e) {
            log.warn("retention: cannot delete sidecar of {} yet: {}", file.getFileName(), e.toString());
        }
    }

    /**
     * Deletes alert snapshots older than the retention window and clears the now-dangling path on the
     * matching {@code alerts} rows: the file is repeatable evidence, the alert row is history and must
     * stay, so the row keeps its camera/zone/time and loses only the path that no longer resolves.
     */
    private int pruneSnapshots() {
        if (snapshotsDir == null || !Files.isDirectory(snapshotsDir)) {
            return 0;
        }
        long cut = nowMs.getAsLong() - policy.retentionDays() * DAY_MS;
        int deleted = 0;
        List<String> removed = new ArrayList<>();
        try (Stream<Path> files = Files.list(snapshotsDir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".png")) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(file).toMillis() < cut
                            && Files.deleteIfExists(file)) {
                        deleted++;
                        removed.add(file.toAbsolutePath().toString());
                    }
                } catch (IOException e) {
                    log.warn("retention: cannot prune snapshot {}: {}", file, e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("retention: cannot list snapshots in {}: {}", snapshotsDir, e.toString());
        }
        int cleared = clearSnapshotPaths(removed);
        if (deleted > 0) {
            log.info("retention: pruned {} alert snapshot(s), cleared {} alert row path(s)", deleted, cleared);
        }
        return deleted;
    }

    private int clearSnapshotPaths(List<String> removed) {
        if (removed.isEmpty()) {
            return 0;
        }
        int cleared = 0;
        try (Connection connection = Database.open(dbFile);
             PreparedStatement ps = connection.prepareStatement(
                     "UPDATE alerts SET snapshot_path = NULL WHERE snapshot_path = ?")) {
            for (String path : removed) {
                ps.setString(1, path);
                cleared += ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("retention: cannot clear pruned snapshot paths: {}", e.toString());
        }
        return cleared;
    }

    private List<Row> loadReady(Connection connection) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, file_path, start_utc, COALESCE(end_utc,0), size_bytes, trigger_type "
                        + "FROM recordings WHERE status='ready'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new Row(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getString(6)));
            }
        }
        return rows;
    }

    private int deleteRow(Connection connection, long id) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM recordings WHERE id=?")) {
            ps.setLong(1, id);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("cannot delete row {}", id, e);
            return 0;
        }
    }

    private int deleteOrphanFiles(Connection connection, Set<String> referenced) {
        if (!Files.isDirectory(recordingsDir)) {
            return 0;
        }
        long graceCut = nowMs.getAsLong() - policy.unkeptGraceMs();
        int deleted = 0;
        try (Stream<Path> files = Files.list(recordingsDir)) {
            for (Path file : files.toList()) {
                if (!file.getFileName().toString().endsWith(".mkv")) {
                    continue;
                }
                if (referenced.contains(file.toAbsolutePath().toString())) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(file).toMillis() > graceCut) {
                        continue; // too new: the engine may be about to announce it
                    }
                    deleteClip(file);
                    deleted++;
                    log.info("reconcile: deleted orphan file {}", file.getFileName());
                } catch (IOException e) {
                    log.warn("reconcile: cannot delete orphan {}", file, e);
                }
            }
        } catch (IOException e) {
            log.warn("reconcile: cannot list {}", recordingsDir, e);
        }
        return deleted;
    }
}
