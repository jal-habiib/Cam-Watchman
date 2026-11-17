package com.cctv.app.label;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cctv.app.db.RecordingRepository;
import com.cctv.contract.ClipFinalized;
import com.cctv.contract.Detection;
import com.cctv.contract.SegmentStarted;
import com.cctv.app.event.SimpleEventBus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClipLabelerTest {

    @TempDir
    Path tempDir;

    private SimpleEventBus bus;
    private RecordingRepository repository;
    private ClipLabeler labeler;
    private Path clip;

    @BeforeEach
    void setUp() throws Exception {
        Path db = tempDir.resolve("clips.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS recordings (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          camera_id INTEGER NOT NULL,
                          file_path TEXT NOT NULL,
                          start_utc INTEGER NOT NULL,
                          end_utc INTEGER,
                          size_bytes INTEGER,
                          trigger_type TEXT NOT NULL DEFAULT 'none',
                          status TEXT NOT NULL DEFAULT 'writing',
                          UNIQUE(camera_id, start_utc))
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS clip_labels (
                          recording_id INTEGER NOT NULL,
                          object_class TEXT NOT NULL,
                          first_seen INTEGER NOT NULL,
                          last_seen INTEGER NOT NULL,
                          max_confidence REAL NOT NULL,
                          count INTEGER NOT NULL,
                          PRIMARY KEY (recording_id, object_class))
                        """);
            }
        }
        bus = new SimpleEventBus();
        repository = RecordingRepository.start(db);
        labeler = new ClipLabeler(bus, repository, true);
        clip = tempDir.resolve("camera-1-abc-00000.mkv");
        Files.writeString(clip, "fake mkv");
    }

    @AfterEach
    void tearDown() {
        labeler.close();
        repository.close();
    }

    @Test
    void aggregatesPerClassAndWritesLabelsAndSidecar() throws Exception {
        long start = 1_700_000_000_000L;
        bus.publish(new SegmentStarted(1, start, clip));
        repository.segmentStarted(1, start, clip);
        bus.publish(new Detection(1, "person", 0.5f, 0.1f, 0.1f, 0.2f, 0.4f, start + 1_000));
        bus.publish(new Detection(1, "person", 0.9f, 0.11f, 0.1f, 0.21f, 0.4f, start + 2_000));
        bus.publish(new Detection(1, "car", 0.7f, 0.5f, 0.5f, 0.8f, 0.8f, start + 3_000));
        // outside the segment window: must be ignored
        bus.publish(new Detection(1, "truck", 0.8f, 0.5f, 0.5f, 0.8f, 0.8f, start - 5_000));

        List<String> rows = new ArrayList<>();
        repository.segmentFinalized(1, start, start + 15_000, 4242, clip, true);
        bus.publish(new ClipFinalized(1, start, start + 15_000, 4242, clip, true));
        assertTrue(repository.flush(Duration.ofSeconds(5)));

        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("clips.db"));
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT object_class, count, max_confidence, first_seen, last_seen"
                             + " FROM clip_labels ORDER BY object_class")) {
            while (rs.next()) {
                rows.add(rs.getString(1) + ":" + rs.getInt(2) + ":" + String.format(java.util.Locale.ROOT, "%.2f", rs.getDouble(3))
                        + ":" + rs.getLong(4) + ":" + rs.getLong(5));
            }
        }
        assertEquals(List.of("car:1:0.70:" + (start + 3_000) + ":" + (start + 3_000),
                "person:2:0.90:" + (start + 1_000) + ":" + (start + 2_000)), rows);

        Path sidecar = tempDir.resolve("camera-1-abc-00000.mkv.json");
        assertTrue(Files.isRegularFile(sidecar), "sidecar written next to the clip");
        String json = Files.readString(sidecar, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schemaVersion\": 1"), json);
        assertTrue(json.contains("\"kept\": true"), json);
        assertTrue(json.contains("\"objectClass\": \"person\""), json);
        assertTrue(json.contains("\"count\": 2"), json);
        assertFalse(json.contains("truck"), "detection outside the segment must not be labelled");
    }

    @Test
    void finalizeOfAnOlderSegmentStillFindsItsLabelsWhenTheNextOneStarted() throws Exception {
        long first = 1_700_000_200_000L;
        long second = first + 15_000;
        bus.publish(new SegmentStarted(1, first, clip));
        repository.segmentStarted(1, first, clip);
        bus.publish(new Detection(1, "person", 0.8f, 0.1f, 0.1f, 0.3f, 0.5f, first + 2_000));
        // the recorder announces the next segment before finalizing the previous one
        bus.publish(new SegmentStarted(1, second, tempDir.resolve("camera-1-abc-00001.mkv")));
        bus.publish(new Detection(1, "person", 0.6f, 0.1f, 0.1f, 0.3f, 0.5f, second + 1_000));
        Files.writeString(tempDir.resolve("camera-1-abc-00001.mkv"), "fake mkv");
        repository.segmentFinalized(1, first, first + 15_000, 500, clip, true);
        bus.publish(new ClipFinalized(1, first, first + 15_000, 500, clip, true));
        assertTrue(repository.flush(Duration.ofSeconds(5)));

        String json = Files.readString(tempDir.resolve("camera-1-abc-00000.mkv.json"),
                StandardCharsets.UTF_8);
        assertTrue(json.contains("\"objectClass\": \"person\""), json);
        assertTrue(json.contains("\"count\": 1"), json);
        assertTrue(json.contains("\"maxConfidence\": 0.800"), json);
    }

    @Test
    void noDetectionsMeansNoLabelRowsButStillASidecar() throws Exception {
        long start = 1_700_000_100_000L;
        bus.publish(new SegmentStarted(1, start, clip));
        repository.segmentStarted(1, start, clip);
        repository.segmentFinalized(1, start, start + 15_000, 100, clip, false);
        bus.publish(new ClipFinalized(1, start, start + 15_000, 100, clip, false));
        assertTrue(repository.flush(Duration.ofSeconds(5)));

        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("clips.db"));
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM clip_labels")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
        String json = Files.readString(tempDir.resolve("camera-1-abc-00000.mkv.json"),
                StandardCharsets.UTF_8);
        assertTrue(json.contains("\"labels\": []"), json);
    }
}
