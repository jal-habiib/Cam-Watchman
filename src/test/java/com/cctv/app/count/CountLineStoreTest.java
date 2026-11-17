package com.cctv.app.count;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cctv.app.db.Database;
import com.cctv.app.db.LineDraft;
import com.cctv.app.db.RecordingRepository;
import com.cctv.contract.CountLine;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CountLineStoreTest {

    @TempDir
    Path tmp;

    private RecordingRepository repository;
    private CountLineStore lines;

    @BeforeEach
    void setUp() throws Exception {
        Path database = tmp.resolve("lines.db");
        try (Connection ignored = Database.open(database)) {
            // schema only
        }
        repository = RecordingRepository.start(database);
        lines = new CountLineStore(database, repository);
        lines.cacheTtlMs(0);
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close();
        }
    }

    @Test
    void savesReloadsAndExposesOnlyActiveLines() throws Exception {
        lines.replaceForCamera(1, List.of(
                new LineDraft("door", 0.10, 0.50, 0.90, 0.50, true),
                new LineDraft("lane", 0.50, 0.10, 0.50, 0.90, false)));
        assertTrue(repository.flush(Duration.ofSeconds(5)));

        List<CountLine> saved = lines.forCamera(1);
        assertEquals(2, saved.size());
        assertEquals("door", saved.get(0).name());
        assertTrue(saved.get(0).id() > 0);
        assertTrue(saved.get(0).active());
        assertFalse(saved.get(1).active());

        List<CountLine> active = lines.activeLines(1);
        assertEquals(1, active.size());
        assertEquals("door", active.get(0).name());
        // point above the line is on one side, below on the other, and the sign flips
        assertTrue(active.get(0).sideOf(0.5, 0.2) * active.get(0).sideOf(0.5, 0.8) < 0);
    }

    @Test
    void secondSaveReplacesTheCameraLines() {
        lines.replaceForCamera(2, List.of(new LineDraft("a", 0.0, 0.5, 1.0, 0.5, true)));
        assertTrue(repository.flush(Duration.ofSeconds(5)));
        lines.replaceForCamera(2, List.of(new LineDraft("b", 0.0, 0.2, 1.0, 0.2, true)));
        assertTrue(repository.flush(Duration.ofSeconds(5)));

        List<CountLine> lines2 = lines.activeLines(2);
        assertEquals(1, lines2.size());
        assertEquals("b", lines2.get(0).name());
    }

    @Test
    void camerasAreIsolated() {
        lines.replaceForCamera(1, List.of(new LineDraft("one", 0.0, 0.5, 1.0, 0.5, true)));
        lines.replaceForCamera(2, List.of(new LineDraft("two", 0.0, 0.5, 1.0, 0.5, true)));
        assertTrue(repository.flush(Duration.ofSeconds(5)));

        assertEquals("one", lines.activeLines(1).get(0).name());
        assertEquals("two", lines.activeLines(2).get(0).name());
    }

    @Test
    void rejectsDegenerateLinesBeforeTouchingTheDatabase() {
        lines.replaceForCamera(3, List.of(new LineDraft("all", 0.0, 0.5, 1.0, 0.5, true)));
        assertTrue(repository.flush(Duration.ofSeconds(5)));

        assertThrows(IllegalArgumentException.class,
                () -> lines.replaceForCamera(3, List.of(new LineDraft("dot", 0.5, 0.5, 0.5, 0.5, true))));
        assertThrows(IllegalArgumentException.class,
                () -> lines.replaceForCamera(3, List.of(new LineDraft(" ", 0.0, 0.5, 1.0, 0.5, true))));

        assertEquals(1, lines.activeLines(3).size());
    }

    @Test
    void togglesAndDeletes() {
        lines.replaceForCamera(4, List.of(new LineDraft("gate", 0.0, 0.5, 1.0, 0.5, true)));
        assertTrue(repository.flush(Duration.ofSeconds(5)));
        long id = lines.activeLines(4).get(0).id();

        lines.setActive(4, id, false);
        assertTrue(repository.flush(Duration.ofSeconds(5)));
        assertTrue(lines.activeLines(4).isEmpty());

        lines.delete(4, id);
        assertTrue(repository.flush(Duration.ofSeconds(5)));
        try {
            assertTrue(lines.forCamera(4).isEmpty());
        } catch (java.sql.SQLException e) {
            throw new AssertionError(e);
        }
    }
}
