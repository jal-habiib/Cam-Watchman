package com.cctv.app.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZoneStoreTest {

    @TempDir
    Path tmp;

    private RecordingRepository repository;
    private ZoneStore zones;

    @BeforeEach
    void setUp() throws Exception {
        Path database = tmp.resolve("zones.db");
        try (Connection ignored = Database.open(database)) {
            // schema only
        }
        repository = RecordingRepository.start(database);
        zones = new ZoneStore(database, repository);
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close();
        }
    }

    @Test
    void replacesSavesAndReloadsZones() {
        zones.replaceForCamera(1, List.of(
                new ZoneDraft("gate", 0.10, 0.10, 0.20, 0.20, true),
                new ZoneDraft("drive", 0.50, 0.50, 0.30, 0.30, true)));
        assertTrue(repository.flush(Duration.ofSeconds(5)));

        List<ZoneStore.Zone> saved = zones.forCamera(1);
        assertEquals(2, saved.size());
        assertEquals("gate", saved.get(0).name());
        assertEquals("drive", saved.get(1).name());
        assertTrue(saved.get(0).id() > 0);
        assertTrue(saved.get(0).contains(0.15, 0.15));
        assertFalse(saved.get(0).contains(0.60, 0.60));
        assertEquals(1, saved.get(0).cameraId());
        assertEquals(0.10, saved.get(0).x(), 1e-9);
        assertEquals(0.20, saved.get(0).toDefinition().w(), 1e-9);
        assertTrue(saved.get(0).toDefinition().containsFeetPoint(
                new com.cctv.contract.Detection(1, "person", 0.9f, 0.10, 0.05, 0.20, 0.25, 1L)));
    }

    @Test
    void secondSaveReplacesTheFirst() {
        zones.replaceForCamera(1, List.of(
                new ZoneDraft("a", 0.10, 0.10, 0.10, 0.10, true),
                new ZoneDraft("b", 0.30, 0.30, 0.10, 0.10, true)));
        assertTrue(repository.flush(Duration.ofSeconds(5)));
        zones.replaceForCamera(1, List.of(new ZoneDraft("only", 0.60, 0.60, 0.20, 0.20, true)));
        assertTrue(repository.flush(Duration.ofSeconds(5)));

        List<ZoneStore.Zone> saved = zones.forCamera(1);
        assertEquals(1, saved.size());
        assertEquals("only", saved.get(0).name());
    }

    @Test
    void camerasDoNotShareZones() {
        zones.replaceForCamera(1, List.of(new ZoneDraft("one", 0.1, 0.1, 0.2, 0.2, true)));
        zones.replaceForCamera(2, List.of(new ZoneDraft("two", 0.3, 0.3, 0.2, 0.2, true)));
        assertTrue(repository.flush(Duration.ofSeconds(5)));

        assertEquals("one", zones.forCamera(1).get(0).name());
        assertEquals("two", zones.forCamera(2).get(0).name());
        assertEquals(2, zones.all().size());
    }

    @Test
    void rejectsRectanglesThatLeaveTheFrame() {
        assertThrows(IllegalArgumentException.class, () -> zones.replaceForCamera(1,
                List.of(new ZoneDraft("wide", 0.90, 0.10, 0.30, 0.10, true))));
        assertThrows(IllegalArgumentException.class, () -> zones.replaceForCamera(1,
                List.of(new ZoneDraft("", 0.10, 0.10, 0.10, 0.10, true))));
        assertThrows(IllegalArgumentException.class, () -> zones.replaceForCamera(1,
                List.of(new ZoneDraft("negative", -0.10, 0.10, 0.10, 0.10, true))));
    }

    @Test
    void togglesAndDeletesOneZone() {
        zones.replaceForCamera(1, List.of(
                new ZoneDraft("gate", 0.10, 0.10, 0.20, 0.20, true),
                new ZoneDraft("drive", 0.50, 0.50, 0.20, 0.20, true)));
        assertTrue(repository.flush(Duration.ofSeconds(5)));
        ZoneStore.Zone gate = zones.forCamera(1).get(0);

        zones.setActive(gate.id(), false);
        assertTrue(repository.flush(Duration.ofSeconds(5)));
        ZoneStore.Zone disabled = zones.byId(gate.id()).orElseThrow();
        assertFalse(disabled.active());
        assertFalse(disabled.contains(0.15, 0.15));

        zones.delete(gate.id());
        assertTrue(repository.flush(Duration.ofSeconds(5)));
        assertTrue(zones.byId(gate.id()).isEmpty());
        assertEquals(1, zones.forCamera(1).size());
    }
}
