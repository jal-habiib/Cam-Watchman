package com.cctv.app.db;

import com.cctv.app.config.CameraConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Camera management store: CRUD, duplicates, persistence and history preservation. */
class CameraStoreTest {

    @TempDir
    Path dir;

    private Path db;
    private CameraStore store;

    @BeforeEach
    void setUp() throws Exception {
        db = dir.resolve("cam-watchman.db");
        try (Connection ignored = Database.open(db)) {
            // create the schema
        }
        store = new CameraStore(db);
    }

    private static CameraStore.Draft draft(String name, String url) {
        return new CameraStore.Draft(name, URI.create(url), null, true, true, true);
    }

    @Test
    void addsAndListsCameras() {
        long id = store.add(draft("Front door", "rtsp://127.0.0.1:8554/front"));
        assertEquals(1L, id);

        List<CameraStore.Camera> all = store.all();
        assertEquals(1, all.size());
        CameraStore.Camera camera = all.get(0);
        assertEquals("Front door", camera.name());
        assertEquals("rtsp://127.0.0.1:8554/front", camera.url().toString());
        assertTrue(camera.record());
        assertTrue(camera.ai());
        assertTrue(camera.enabled());
        assertEquals(1, store.enabled().size());
        assertNotNull(store.byId(id));
        assertNull(store.byId(999));
    }

    @Test
    void importedPropertiesAreOnlyUsedWhenTheTableIsEmpty() {
        List<CameraConfig> configured = List.of(
                new CameraConfig(7, "File cam", URI.create("rtsp://127.0.0.1:8554/a"), null, true, true),
                new CameraConfig(8, "File sub", null, URI.create("rtsp://127.0.0.1:8554/b"), false, false));

        assertEquals(2, store.importIfEmpty(configured));
        assertEquals(2, store.all().size());
        assertEquals(0, store.importIfEmpty(configured), "second import must not duplicate rows");

        store.add(draft("Manual", "rtsp://127.0.0.1:8554/c"));
        assertEquals(0, store.importIfEmpty(configured), "manual rows are never overwritten");
        assertEquals(3, store.all().size());
    }

    @Test
    void rejectsInvalidDrafts() {
        assertThrows(IllegalArgumentException.class,
                () -> store.add(new CameraStore.Draft(" ", null, URI.create("rtsp://h/s"), false, false, true)));
        assertThrows(IllegalArgumentException.class,
                () -> store.add(new CameraStore.Draft("x", URI.create("http://h/s"), null, true, false, true)));
        assertThrows(IllegalArgumentException.class,
                () -> store.add(new CameraStore.Draft("x", URI.create("rtsp:///nohost"), null, true, false, true)));
        assertThrows(IllegalArgumentException.class,
                () -> store.add(new CameraStore.Draft("x", null, null, false, false, true)),
                "a camera without any URL is useless");
        assertThrows(IllegalArgumentException.class,
                () -> store.add(new CameraStore.Draft("x", null, URI.create("rtsp://h/sub"), true, false, true)),
                "recording needs a main stream URL");
        assertEquals(0, store.all().size());
    }

    @Test
    void rejectsDuplicateNamesAndUrls() {
        store.add(draft("Gate", "rtsp://127.0.0.1:8554/gate"));
        assertThrows(IllegalArgumentException.class,
                () -> store.add(draft("gate", "rtsp://127.0.0.1:8554/other")),
                "names are compared case-insensitively");
        assertThrows(IllegalArgumentException.class,
                () -> store.add(draft("Other", "rtsp://127.0.0.1:8554/gate")),
                "the same URL cannot be used twice");
        assertEquals(1, store.all().size());
    }

    @Test
    void updatesEditableValuesButNotItsOwnDuplicates() {
        long id = store.add(draft("Gate", "rtsp://127.0.0.1:8554/gate"));
        store.update(id, new CameraStore.Draft("Front gate", URI.create("rtsp://127.0.0.1:8554/g2"),
                URI.create("rtsp://127.0.0.1:8554/g2sub"), true, false, false));

        CameraStore.Camera camera = store.byId(id);
        assertEquals("Front gate", camera.name());
        assertEquals("rtsp://127.0.0.1:8554/g2", camera.url().toString());
        assertEquals("rtsp://127.0.0.1:8554/g2sub", camera.substreamUrl().toString());
        assertFalse(camera.ai());
        assertFalse(camera.enabled());

        assertThrows(IllegalArgumentException.class,
                () -> store.update(999, draft("Nope", "rtsp://127.0.0.1:8554/none")));
    }

    @Test
    void disablingKeepsTheCameraButStopsItFromStarting() {
        long id = store.add(draft("Gate", "rtsp://127.0.0.1:8554/gate"));
        store.setEnabled(id, false);

        assertEquals(1, store.all().size());
        assertEquals(0, store.enabled().size());
        assertFalse(store.byId(id).enabled());

        store.setEnabled(id, true);
        assertEquals(1, store.enabled().size());
    }

    @Test
    void rejectsAFifthCameraBecauseTheWallHoldsFour() {
        for (int i = 1; i <= CameraStore.MAX_CAMERAS; i++) {
            store.add(draft("Cam " + i, "rtsp://127.0.0.1:8554/c" + i));
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> store.add(draft("Cam five", "rtsp://127.0.0.1:8554/c5")));
        assertTrue(error.getMessage().contains("at most 4"), error.getMessage());
        assertEquals(CameraStore.MAX_CAMERAS, store.activeCount());
    }

    @Test
    void removingIsSoftAndPreservesHistory() throws Exception {
        long id = store.add(draft("Gate", "rtsp://127.0.0.1:8554/gate"));
        try (Connection connection = Database.open(db);
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO recordings(camera_id, file_path, start_utc, end_utc, size_bytes,"
                                + " trigger_type, status) VALUES (?, ?, ?, ?, ?, 'detection', 'ready')")) {
            ps.setLong(1, id);
            ps.setString(2, dir.resolve("clip.mkv").toString());
            ps.setLong(3, 1_000L);
            ps.setLong(4, 2_000L);
            ps.setLong(5, 123L);
            ps.executeUpdate();
        }

        store.remove(id);

        assertEquals(0, store.all().size());
        assertEquals(0, store.enabled().size());
        try (Connection connection = Database.open(db);
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT COUNT(*) FROM recordings WHERE camera_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "recordings of a removed camera stay in the database");
            }
        }
    }

    @Test
    void survivesAReopen() {
        long id = store.add(draft("Gate", "rtsp://127.0.0.1:8554/gate"));
        CameraStore reopened = new CameraStore(db);
        assertNotNull(reopened.byId(id));
        assertEquals("Gate", reopened.byId(id).name());
    }

    @Test
    void displayUrlHidesCredentials() {
        CameraStore.Camera camera = new CameraStore.Camera(1, "Secure",
                URI.create("rtsp://user:secret@cam.example/stream"), null, false, false, true);
        assertEquals("rtsp://***@cam.example/stream", camera.displayUrl());
        assertEquals("rtsp://***@cam.example/sub", new CameraStore.Camera(2, "Sub", null,
                URI.create("rtsp://user:secret@cam.example/sub"), false, false, true).displayUrl());
    }
}

