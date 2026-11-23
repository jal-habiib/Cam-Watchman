package com.cctv.app.db;

import com.cctv.contract.CountLine;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only writer of the {@code recordings}/{@code alerts} tables.
 *
 * <p>Publishers (engine listeners, UI actions) enqueue; a single daemon thread opens the
 * connection, drains up to {@value #BATCH_SIZE} commands and commits them in one transaction.
 * The queue is bounded and publishers never block the JavaFX thread. Any rejected command is
 * counted and logged as an evidence-persistence failure rather than silently disappearing.
 */
public final class RecordingRepository implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RecordingRepository.class);
    static final int BATCH_SIZE = 64;
    static final int QUEUE_CAPACITY = 1000;

    private final Path dbFile;
    private final BlockingQueue<DbCommand> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicLong droppedCommands = new AtomicLong();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final Thread writer;
    private volatile long written;

    private RecordingRepository(Path dbFile) {
        this.dbFile = dbFile;
        this.writer = new Thread(this::writeLoop, "db-writer");
        this.writer.setDaemon(true);
    }

    public static RecordingRepository start(Path dbFile) {
        RecordingRepository repository = new RecordingRepository(dbFile);
        repository.writer.start();
        return repository;
    }

    /** Engine: a segment file was opened (plan 3a). Inserts the row in state {@code writing}. */
    public void segmentStarted(long cameraId, long startUtcMs, Path file) {
        offer(new Started(cameraId, startUtcMs, file.toAbsolutePath().toString()));
    }

    /**
     * Engine: a segment was closed. {@code keep} comes from the retainer (detection overlap,
     * plan 3b); non-kept segments are removed after the grace period.
     */
    public void segmentFinalized(long cameraId, long startUtcMs, long endUtcMs, long sizeBytes,
                                 Path file, boolean keep) {
        offer(new Finalized(cameraId, startUtcMs, endUtcMs, sizeBytes,
                file.toAbsolutePath().toString(), keep));
    }

    /**
     * Engine: an intrusion alert fired; the app has already written the snapshot PNG. The recording
     * is located by its segment key {@code (cameraId, startUtcMs)}, so no database id crosses the
     * contract boundary; the column stays null when no such clip is known.
     */
    public void alert(long cameraId, long zoneId, long recordingStartUtcMs, Path snapshot,
                      long createdUtcMs) {
        offer(new Alert(cameraId, zoneId, recordingStartUtcMs,
                snapshot == null ? null : snapshot.toAbsolutePath().toString(), createdUtcMs));
    }

    /** Phase 5a: replace all zones of one camera (delete + insert in one writer transaction). */
    public void replaceZones(long cameraId, List<ZoneDraft> zones) {
        offer(new ReplaceZones(cameraId, List.copyOf(zones)));
    }

    /** Phase 5a: enable/disable one zone without touching its geometry. */
    public void setZoneActive(long zoneId, boolean active) {
        offer(new SetZoneActive(zoneId, active));
    }

    /** Phase 5a: delete one zone. */
    public void deleteZone(long zoneId) {
        offer(new DeleteZone(zoneId));
    }

    /**
     * Phase 4a: replace the per-class label rows of one clip (plan 4a). The clip is located by its
     * segment key {@code (cameraId, startUtcMs)} inside the writer thread, so the app never passes a
     * database id around; an unknown clip is dropped with a debug log.
     */
    public void clipLabels(long cameraId, long startUtcMs, List<ClipLabel> labels) {
        offer(new Labels(cameraId, startUtcMs, List.copyOf(labels)));
    }

    /** Phase 7b: one line crossing from the engine, folded into the daily summary as well. */
    public void crossing(long cameraId, long lineId, long trackId, String direction,
                         long crossedUtcMs, String dateKey) {
        offer(new Crossing(cameraId, lineId, trackId, direction, crossedUtcMs, dateKey));
    }

    /** Phase 7b: replace every counting line of one camera. */
    public void replaceCountLines(long cameraId, List<CountLine> lines) {
        offer(new ReplaceCountLines(cameraId, List.copyOf(lines)));
    }

    /** Phase 7b: enable/disable a counting line. */
    public void setCountLineActive(long lineId, boolean active) {
        offer(new SetCountLineActive(lineId, active));
    }

    /** Phase 7b: delete a counting line. */
    public void deleteCountLine(long lineId) {
        offer(new DeleteCountLine(lineId));
    }

    /** Phase 7c: clear one camera's events for a local day and zero its summary row. */
    public void resetCounters(long cameraId, long fromUtcMs, long toUtcMs, String dateKey) {
        offer(new ResetCounters(cameraId, fromUtcMs, toUtcMs, dateKey));
    }

    public long writtenCount() {
        return written;
    }

    public long droppedCommandCount() {
        return droppedCommands.get();
    }

    public int queueDepth() {
        return queue.size();
    }

    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure.get());
    }

    private void offer(DbCommand command) {
        if (!running.get()) {
            long dropped = droppedCommands.incrementAndGet();
            if (dropped == 1 || dropped % 100 == 0) {
                log.error("database repository is closed - rejected {} command(s); latest {}", dropped, command);
            }
            return;
        }
        if (!writer.isAlive()) {
            long dropped = droppedCommands.incrementAndGet();
            if (dropped == 1 || dropped % 100 == 0) {
                log.error("database writer is not running - rejected {} command(s); latest {}", dropped,
                        command, failure.get());
            }
            return;
        }
        if (!queue.offer(command)) {
            long dropped = droppedCommands.incrementAndGet();
            if (dropped == 1 || dropped % 100 == 0) {
                log.error("database queue full ({}) - dropped {} command(s); latest {}", QUEUE_CAPACITY,
                        dropped, command);
            }
        }
    }

    /**
     * Blocks until everything enqueued before this call has been committed; used by tests and by
     * the shutdown path. Returns false on timeout, a failed batch, or a concurrent queue drop.
     */
    public boolean flush(Duration timeout) {
        if (!writer.isAlive()) {
            return false;
        }
        Barrier barrier = new Barrier(new CountDownLatch(1), new AtomicBoolean());
        long droppedBefore = droppedCommands.get();
        long timeoutNanos = Math.max(0L, timeout.toNanos());
        long deadline = System.nanoTime() + timeoutNanos;
        try {
            if (!queue.offer(barrier, timeoutNanos, TimeUnit.NANOSECONDS)) {
                return false;
            }
            long remaining = Math.max(0L, deadline - System.nanoTime());
            return barrier.latch().await(remaining, TimeUnit.NANOSECONDS)
                    && barrier.committed().get() && droppedCommands.get() == droppedBefore;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public synchronized void close() {
        if (!running.get()) {
            return;
        }
        // Place the barrier while the writer is still accepting work; only then mark it closed.
        // Otherwise the writer could observe an empty queue, exit, and miss a barrier added later.
        boolean flushed = flush(Duration.ofSeconds(5));
        running.set(false);
        if (!flushed) {
            log.error("database shutdown barrier did not confirm a clean commit; queue={} rejected={}",
                    queue.size(), droppedCommands.get());
        }
        try {
            // The writer drains the queue naturally after running=false. Do not interrupt it before
            // this join: interrupting queue.poll() would abandon commands that are still pending.
            writer.join(Duration.ofSeconds(10));
            if (writer.isAlive()) {
                log.error("database writer did not drain within 10 seconds; {} command(s) remain", queue.size());
                writer.interrupt();
                writer.join(Duration.ofSeconds(1));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("interrupted while waiting for database writer shutdown; {} command(s) remain", queue.size());
        }
        if (!queue.isEmpty()) {
            log.error("database shutdown left {} uncommitted command(s)", queue.size());
        }
        if (droppedCommands.get() > 0) {
            log.error("database shutdown completed after rejecting {} command(s)", droppedCommands.get());
        }
        stopped.countDown();
    }

    private void writeLoop() {
        try (Connection connection = Database.open(dbFile)) {
            List<DbCommand> batch = new ArrayList<>(BATCH_SIZE);
            while (running.get() || !queue.isEmpty()) {
                DbCommand first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.clear();
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                executeBatch(connection, batch);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            failure.set(e);
            log.error("database writer stopped", e);
        } finally {
            stopped.countDown();
        }
    }

    private static final int MAX_BATCH_RETRIES = 3;

    private void executeBatch(Connection connection, List<DbCommand> batch) {
        List<Barrier> barriers = new ArrayList<>();
        for (DbCommand command : batch) {
            if (command instanceof Barrier barrier) {
                barriers.add(barrier);
            }
        }

        boolean committed = false;
        int retries = 0;
        while (!committed) {
            try {
                connection.setAutoCommit(false);
                for (DbCommand command : batch) {
                    if (!(command instanceof Barrier)) {
                        command.apply(connection);
                    }
                }
                connection.commit();
                failure.set(null);
                written += batch.size();
                committed = true;
            } catch (SQLException e) {
                failure.set(e);
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                if (retries >= MAX_BATCH_RETRIES) {
                    long rejected = batch.stream().filter(command -> !(command instanceof Barrier)).count();
                    droppedCommands.addAndGet(rejected);
                    log.error("database batch permanently failed after {} attempt(s); {} metadata command(s) rejected",
                            retries + 1, rejected, e);
                    break;
                }
                long backoffMs = switch (retries) {
                    case 0 -> 100L;
                    case 1 -> 500L;
                    default -> 1_500L;
                };
                retries++;
                log.warn("database batch failed; retry {}/{} in {} ms: {}",
                        retries, MAX_BATCH_RETRIES, backoffMs, e.toString());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    long rejected = batch.stream().filter(command -> !(command instanceof Barrier)).count();
                    droppedCommands.addAndGet(rejected);
                    log.error("database batch interrupted during retry; {} metadata command(s) rejected",
                            rejected, interrupted);
                    break;
                }
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException resetFailure) {
                    failure.set(resetFailure);
                    log.warn("could not restore database auto-commit after batch: {}", resetFailure.toString());
                }
            }
        }
        boolean result = committed;
        barriers.forEach(barrier -> barrier.release(result));
    }

    // ------------------------------------------------------------------ commands

    private record Started(long cameraId, long startUtcMs, String file) implements DbCommand {
        @Override
        public void apply(Connection connection) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO recordings(camera_id, file_path, start_utc, status) VALUES (?,?,?,'writing') "
                            + "ON CONFLICT(camera_id, start_utc) DO UPDATE SET file_path=excluded.file_path, "
                            + "status='writing'")) {
                ps.setLong(1, cameraId);
                ps.setString(2, file);
                ps.setLong(3, startUtcMs);
                ps.executeUpdate();
            }
        }
    }

    private record Finalized(long cameraId, long startUtcMs, long endUtcMs, long sizeBytes,
                             String file, boolean keep) implements DbCommand {
        @Override
        public void apply(Connection connection) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO recordings(camera_id, file_path, start_utc, end_utc, size_bytes, "
                            + "trigger_type, status) VALUES (?,?,?,?,?,?,'ready') "
                            + "ON CONFLICT(camera_id, start_utc) DO UPDATE SET file_path=excluded.file_path, "
                            + "end_utc=excluded.end_utc, size_bytes=excluded.size_bytes, "
                            + "trigger_type=excluded.trigger_type, status='ready'")) {
                ps.setLong(1, cameraId);
                ps.setString(2, file);
                ps.setLong(3, startUtcMs);
                ps.setLong(4, endUtcMs);
                ps.setLong(5, sizeBytes);
                ps.setString(6, keep ? "detection" : "none");
                ps.executeUpdate();
            }
        }
    }
    private record Alert(long cameraId, long zoneId, long recordingStartUtcMs, String snapshot,
                         long createdUtcMs) implements DbCommand {
        @Override
        public void apply(Connection connection) throws SQLException {
            Long recordingId = null;
            if (recordingStartUtcMs > 0) {
                try (PreparedStatement find = connection.prepareStatement(
                        "SELECT id FROM recordings WHERE camera_id = ? AND start_utc = ?")) {
                    find.setLong(1, cameraId);
                    find.setLong(2, recordingStartUtcMs);
                    try (var rs = find.executeQuery()) {
                        if (rs.next()) {
                            recordingId = rs.getLong(1);
                        }
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO alerts(camera_id, zone_id, recording_id, snapshot_path, created_utc) "
                            + "VALUES (?,?,?,?,?)")) {
                ps.setLong(1, cameraId);
                ps.setLong(2, zoneId);
                if (recordingId == null) {
                    ps.setNull(3, java.sql.Types.INTEGER);
                } else {
                    ps.setLong(3, recordingId);
                }
                ps.setString(4, snapshot);
                ps.setLong(5, createdUtcMs);
                ps.executeUpdate();
            }
        }
    }

    private record ReplaceZones(long cameraId, List<ZoneDraft> zones) implements DbCommand {
        @Override
        public void apply(Connection connection) throws SQLException {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM zones WHERE camera_id = ?")) {
                delete.setLong(1, cameraId);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO zones(camera_id, name, x, y, w, h, active) VALUES (?,?,?,?,?,?,?)")) {
                for (ZoneDraft zone : zones) {
                    insert.setLong(1, cameraId);
                    insert.setString(2, zone.name());
                    insert.setDouble(3, zone.x());
                    insert.setDouble(4, zone.y());
                    insert.setDouble(5, zone.w());
                    insert.setDouble(6, zone.h());
                    insert.setInt(7, zone.active() ? 1 : 0);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    private record SetZoneActive(long zoneId, boolean active) implements DbCommand {
        @Override
        public void apply(Connection connection) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE zones SET active = ? WHERE id = ?")) {
                ps.setInt(1, active ? 1 : 0);
                ps.setLong(2, zoneId);
                ps.executeUpdate();
            }
        }
    }

    private record DeleteZone(long zoneId) implements DbCommand {
        @Override
        public void apply(Connection connection) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM zones WHERE id = ?")) {
                ps.setLong(1, zoneId);
                ps.executeUpdate();
            }
        }
    }

    private record Labels(long cameraId, long startUtcMs, List<ClipLabel> labels) implements DbCommand {
        @Override
        public void apply(Connection connection) throws SQLException {
            Long recordingId = null;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id FROM recordings WHERE camera_id = ? AND start_utc = ?")) {
                ps.setLong(1, cameraId);
                ps.setLong(2, startUtcMs);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        recordingId = rs.getLong(1);
                    }
                }
            }
            if (recordingId == null) {
                log.debug("labels for unknown clip camera {} start {} dropped", cameraId, startUtcMs);
                return;
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM clip_labels WHERE recording_id = ?")) {
                delete.setLong(1, recordingId);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO clip_labels(recording_id, object_class, first_seen, last_seen,"
                            + " max_confidence, count) VALUES (?,?,?,?,?,?)")) {
                for (ClipLabel label : labels) {
                    insert.setLong(1, recordingId);
                    insert.setString(2, label.objectClass());
                    insert.setLong(3, label.firstSeenUtcMs());
                    insert.setLong(4, label.lastSeenUtcMs());
                    insert.setDouble(5, label.maxConfidence());
                    insert.setInt(6, label.count());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    private record Crossing(long cameraId, long lineId, long trackId, String direction,
                            long crossedUtcMs, String dateKey) implements DbCommand {
        @Override
        public void apply(Connection connection) throws SQLException {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO count_events(camera_id, line_id, track_id, direction,"
                            + " crossed_utc) VALUES (?,?,?,?,?)")) {
                insert.setLong(1, cameraId);
                insert.setLong(2, lineId);
                insert.setLong(3, trackId);
                insert.setString(4, direction);
                insert.setLong(5, crossedUtcMs);
                insert.executeUpdate();
            }
            boolean inbound = "IN".equals(direction);
            try (PreparedStatement upsert = connection.prepareStatement(
                    "INSERT INTO count_summaries(date, camera_id, count_in, count_out)"
                            + " VALUES (?,?,?,?) ON CONFLICT(date, camera_id) DO UPDATE SET"
                            + " count_in = count_in + excluded.count_in,"
                            + " count_out = count_out + excluded.count_out")) {
                upsert.setString(1, dateKey);
                upsert.setLong(2, cameraId);
                upsert.setInt(3, inbound ? 1 : 0);
                upsert.setInt(4, inbound ? 0 : 1);
                upsert.executeUpdate();
            }
        }
    }

    private record ReplaceCountLines(long cameraId, List<CountLine> lines) implements DbCommand {
        @Override
        public void apply(Connection connection) throws SQLException {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM count_lines WHERE camera_id = ?")) {
                delete.setLong(1, cameraId);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO count_lines(camera_id, name, x1, y1, x2, y2, active)"
                            + " VALUES (?,?,?,?,?,?,?)")) {
                for (CountLine line : lines) {
                    insert.setLong(1, cameraId);
                    insert.setString(2, line.name());
                    insert.setDouble(3, line.x1());
                    insert.setDouble(4, line.y1());
                    insert.setDouble(5, line.x2());
                    insert.setDouble(6, line.y2());
                    insert.setInt(7, line.active() ? 1 : 0);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    private record SetCountLineActive(long lineId, boolean active) implements DbCommand {
        @Override
        public void apply(Connection connection) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE count_lines SET active = ? WHERE id = ?")) {
                ps.setInt(1, active ? 1 : 0);
                ps.setLong(2, lineId);
                ps.executeUpdate();
            }
        }
    }

    private record DeleteCountLine(long lineId) implements DbCommand {
        @Override
        public void apply(Connection connection) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM count_lines WHERE id = ?")) {
                ps.setLong(1, lineId);
                ps.executeUpdate();
            }
        }
    }

    private record ResetCounters(long cameraId, long fromUtcMs, long toUtcMs, String dateKey)
            implements DbCommand {
        @Override
        public void apply(Connection connection) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM count_events WHERE camera_id = ? AND crossed_utc BETWEEN ? AND ?")) {
                ps.setLong(1, cameraId);
                ps.setLong(2, fromUtcMs);
                ps.setLong(3, toUtcMs);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE count_summaries SET count_in = 0, count_out = 0"
                            + " WHERE date = ? AND camera_id = ?")) {
                ps.setString(1, dateKey);
                ps.setLong(2, cameraId);
                ps.executeUpdate();
            }
        }
    }

    private record Barrier(CountDownLatch latch, AtomicBoolean committed) implements DbCommand {
        @Override
        public void apply(Connection connection) {
            // executed in the batch pass-through only
        }

        void release(boolean success) {
            committed.set(success);
            latch.countDown();
        }
    }
}
