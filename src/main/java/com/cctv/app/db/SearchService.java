package com.cctv.app.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only clip search for Phase 4c: object text plus a date range over {@code recordings} and
 * {@code clip_labels} (plan P4). Rows whose file has disappeared are never returned — that is the
 * acceptance rule "search never returns a missing clip" — they are counted so the UI can say how
 * many were skipped.
 */
public final class SearchService {

    private static final int DEFAULT_LIMIT = 200;

    private SearchService() {
    }

    /** Search parameters; {@code objectText} may be blank (date-only browsing). */
    public record Query(String objectText, long fromUtcMs, long toUtcMs, int limit) {

        public static Query of(String objectText, long fromUtcMs, long toUtcMs) {
            return new Query(objectText == null ? "" : objectText.trim(), fromUtcMs, toUtcMs, DEFAULT_LIMIT);
        }
    }

    /** Per-class aggregate for one clip (mirrors a {@code clip_labels} row). */
    public record Label(String objectClass, int count, double maxConfidence, long firstSeenUtcMs,
                        long lastSeenUtcMs) {
    }

    public record Hit(long recordingId, long cameraId, Path file, long startUtcMs, long endUtcMs,
                      long sizeBytes, String triggerType, List<Label> labels) {

        public long durationMs() {
            return Math.max(0, endUtcMs - startUtcMs);
        }

        public String objects() {
            return labels.stream().map(Label::objectClass).reduce((a, b) -> a + ", " + b).orElse("");
        }
    }

    public record Result(List<Hit> hits, int skippedMissingFiles) {
    }

    public static Result search(Path dbFile, Query query) throws SQLException {
        List<Hit> hits = new ArrayList<>();
        int skipped = 0;
        String object = query.objectText() == null ? "" : query.objectText().trim();
        try (Connection connection = Database.open(dbFile)) {
            String sql = """
                    SELECT r.id, r.camera_id, r.file_path, r.start_utc, COALESCE(r.end_utc, r.start_utc),
                           r.size_bytes, r.trigger_type
                      FROM recordings r
                     WHERE r.status = 'ready'
                       AND r.start_utc >= ?
                       AND r.start_utc <= ?
                       AND (? = '' OR EXISTS (SELECT 1 FROM clip_labels l
                                               WHERE l.recording_id = r.id
                                                 AND lower(l.object_class) LIKE lower(?)))
                     ORDER BY r.start_utc DESC
                     LIMIT ?
                    """;
            String like = "%" + object + "%";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, query.fromUtcMs());
                ps.setLong(2, query.toUtcMs());
                ps.setString(3, object);
                ps.setString(4, like);
                ps.setInt(5, query.limit() <= 0 ? DEFAULT_LIMIT : query.limit());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Path file = Path.of(rs.getString("file_path"));
                        if (!Files.isRegularFile(file)) {
                            skipped++;
                            continue;
                        }
                        long id = rs.getLong("id");
                        hits.add(new Hit(id, rs.getLong("camera_id"), file, rs.getLong("start_utc"),
                                rs.getLong(5), rs.getLong("size_bytes"), rs.getString("trigger_type"),
                                labels(connection, id)));
                    }
                }
            }
        }
        return new Result(hits, skipped);
    }

    private static List<Label> labels(Connection connection, long recordingId) throws SQLException {
        List<Label> labels = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT object_class, count, max_confidence, first_seen, last_seen
                  FROM clip_labels WHERE recording_id = ? ORDER BY count DESC, object_class
                """)) {
            ps.setLong(1, recordingId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    labels.add(new Label(rs.getString(1), rs.getInt(2), rs.getDouble(3),
                            rs.getLong(4), rs.getLong(5)));
                }
            }
        }
        return labels;
    }
}
