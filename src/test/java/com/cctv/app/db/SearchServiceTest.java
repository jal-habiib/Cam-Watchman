package com.cctv.app.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchServiceTest {

    @TempDir
    Path dir;

    private Path db;
    private Path recordings;

    @BeforeEach
    void setUp() throws Exception {
        db = dir.resolve("test.db");
        recordings = Files.createDirectories(dir.resolve("recordings"));
        try (Connection connection = Database.open(db)) {
            // two files exist, one row points at a deleted file
            insert(connection, 1, record("a.mkv"), 1_000_000, 1_015_000, "detection", "ready");
            insert(connection, 2, record("b.mkv"), 2_000_000, 2_015_000, "detection", "ready");
            insert(connection, 3, record("gone.mkv"), 3_000_000, 3_015_000, "detection", "ready");
            insert(connection, 4, record("c.mkv"), 4_000_000, 4_015_000, "none", "ready");
        }
        label(1, "car", 5, 0.91);
        label(2, "person", 2, 0.80);
        label(3, "car", 9, 0.95);
        label(4, "car", 1, 0.60);
    }

    private Path record(String name) throws Exception {
        Path file = recordings.resolve(name);
        if (!name.equals("gone.mkv")) {
            Files.writeString(file, "fake mkv bytes");
        }
        return file;
    }

    private void insert(Connection c, long id, Path file, long start, long end, String trigger, String status)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO recordings (id, camera_id, file_path, start_utc, end_utc, size_bytes, trigger_type, status)
                VALUES (?, 1, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setLong(1, id);
            ps.setString(2, file.toString());
            ps.setLong(3, start);
            ps.setLong(4, end);
            ps.setLong(5, Files.exists(file) ? Files.size(file) : 1234);
            ps.setString(6, trigger);
            ps.setString(7, status);
            ps.executeUpdate();
        }
    }

    private void label(long id, String objectClass, int count, double confidence) throws Exception {
        try (Connection c = Database.open(db);
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO clip_labels (recording_id, object_class, first_seen, last_seen,
                                              max_confidence, count) VALUES (?, ?, 0, 1, ?, ?)
                     """)) {
            ps.setLong(1, id);
            ps.setString(2, objectClass);
            ps.setDouble(3, confidence);
            ps.setInt(4, count);
            ps.executeUpdate();
        }
    }

    @Test
    void findsEveryClipWithTheObject() throws Exception {
        var result = SearchService.search(db, SearchService.Query.of("car", 0, Long.MAX_VALUE / 4));
        assertEquals(2, result.hits().size(), "rows 3 (missing file) and 4 (no clip_labels) excluded");
        assertEquals(1, result.skippedMissingFiles(), "only gone.mkv is missing");
        assertTrue(result.hits().stream().allMatch(hit -> hit.objects().contains("car")));
        assertEquals("a.mkv", result.hits().get(1).file().getFileName().toString(), "newest first");
    }

    @Test
    void filtersByDateRange() throws Exception {
        var result = SearchService.search(db, SearchService.Query.of("", 3_500_000, 4_500_000));
        assertEquals(1, result.hits().size());
        assertEquals(4, result.hits().get(0).recordingId());
    }

    @Test
    void blankObjectListsAllReadyClips() throws Exception {
        var result = SearchService.search(db, SearchService.Query.of("  ", 0, Long.MAX_VALUE / 4));
        assertEquals(3, result.hits().size(), "one missing-file row skipped");
    }

    @Test
    void objectMatchIsCaseInsensitiveAndPartial() throws Exception {
        var result = SearchService.search(db, SearchService.Query.of("CAr", 0, Long.MAX_VALUE / 4));
        assertEquals(2, result.hits().size());
    }

    @Test
    void unknownObjectReturnsNothing() throws Exception {
        var result = SearchService.search(db, SearchService.Query.of("elephant", 0, Long.MAX_VALUE / 4));
        assertTrue(result.hits().isEmpty());
    }

    @Test
    void labelsAreAggregatedPerClass() throws Exception {
        var result = SearchService.search(db, SearchService.Query.of("car", 0, Long.MAX_VALUE / 4));
        var label = result.hits().get(1).labels().get(0);
        assertEquals("car", label.objectClass());
        assertEquals(5, label.count());
        assertEquals(0.91, label.maxConfidence(), 1e-9);
        assertEquals(Instant.ofEpochMilli(0).toEpochMilli(), label.firstSeenUtcMs());
    }
}
