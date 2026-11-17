package com.cctv.app.count;

import com.cctv.app.db.Database;
import com.cctv.app.db.LineDraft;
import com.cctv.app.db.RecordingRepository;
import com.cctv.contract.CountLine;
import com.cctv.contract.LineSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 7b: counting-line storage plus the read-only {@link LineSource} the engine polls.
 *
 * <p>Writes go through {@link RecordingRepository}'s single writer thread (one-writer rule); reads
 * open their own WAL connection. {@link #activeLines(long)} is cached for a short TTL because the
 * engine calls it once per inference frame and must never touch the database on that thread.
 */
public final class CountLineStore implements LineSource, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CountLineStore.class);

    private record Cached(List<CountLine> lines, long loadedAtMs) {
    }

    private final Path dbFile;
    private final RecordingRepository repository;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();
    private volatile long ttlMs = 2_000;

    public CountLineStore(Path dbFile, RecordingRepository repository) {
        this.dbFile = dbFile;
        this.repository = repository;
    }

    /** Test hook: shorten the cache TTL to make polling deterministic. */
    void cacheTtlMs(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    @Override
    public List<CountLine> activeLines(long cameraId) {
        Cached cached = cache.get(cameraId);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAtMs() <= ttlMs) {
            return cached.lines();
        }
        List<CountLine> lines;
        try {
            lines = forCamera(cameraId).stream().filter(CountLine::active).toList();
        } catch (SQLException e) {
            log.warn("cannot read counting lines for camera {}: {}", cameraId, e.toString());
            return cached == null ? List.of() : cached.lines();
        }
        cache.put(cameraId, new Cached(lines, now));
        return lines;
    }

    public List<CountLine> forCamera(long cameraId) throws SQLException {
        try (Connection connection = Database.open(dbFile);
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT id, camera_id, name, x1, y1, x2, y2, active FROM count_lines"
                             + " WHERE camera_id = ? ORDER BY id")) {
            ps.setLong(1, cameraId);
            try (ResultSet rs = ps.executeQuery()) {
                List<CountLine> lines = new ArrayList<>();
                while (rs.next()) {
                    lines.add(read(rs));
                }
                return lines;
            }
        }
    }

    public List<CountLine> all() throws SQLException {
        try (Connection connection = Database.open(dbFile);
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT id, camera_id, name, x1, y1, x2, y2, active FROM count_lines"
                             + " ORDER BY camera_id, id");
             ResultSet rs = ps.executeQuery()) {
            List<CountLine> lines = new ArrayList<>();
            while (rs.next()) {
                lines.add(read(rs));
            }
            return lines;
        }
    }

    /** Replaces every line of one camera, validating all drafts before anything is written. */
    public void replaceForCamera(long cameraId, List<LineDraft> lines) {
        List<CountLine> checked = new ArrayList<>(lines.size());
        for (LineDraft draft : lines) {
            checked.add(new CountLine(0, cameraId, draft.name(), draft.x1(), draft.y1(),
                    draft.x2(), draft.y2(), draft.active()));
        }
        repository.replaceCountLines(cameraId, checked);
        invalidate(cameraId);
        log.info("count lines saved: camera {} -> {} line(s)", cameraId, checked.size());
    }

    public void setActive(long cameraId, long lineId, boolean active) {
        repository.setCountLineActive(lineId, active);
        invalidate(cameraId);
    }

    public void delete(long cameraId, long lineId) {
        repository.deleteCountLine(lineId);
        invalidate(cameraId);
    }

    public void invalidate(long cameraId) {
        cache.remove(cameraId);
    }

    private static CountLine read(ResultSet rs) throws SQLException {
        return new CountLine(rs.getLong("id"), rs.getLong("camera_id"), rs.getString("name"),
                rs.getDouble("x1"), rs.getDouble("y1"), rs.getDouble("x2"), rs.getDouble("y2"),
                rs.getInt("active") != 0);
    }

    @Override
    public void close() {
        cache.clear();
    }
}
