package com.cctv.app.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cctv.app.db.Database;
import com.cctv.app.db.RecordingRepository;
import com.cctv.app.event.SimpleEventBus;
import com.cctv.contract.IntrusionEvent;
import com.cctv.contract.ZoneAlert;
import com.cctv.contract.ZoneDefinition;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AlertServiceTest {

    @TempDir
    Path tmp;

    private Path database;
    private RecordingRepository repository;
    private SimpleEventBus bus;

    @BeforeEach
    void setUp() throws Exception {
        database = tmp.resolve("alerts.db");
        try (Connection ignored = Database.open(database)) {
            // schema only
        }
        repository = RecordingRepository.start(database);
        bus = new SimpleEventBus();
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close();
        }
    }

    @Test
    void writesSnapshotRowAndPublishesAlertLinkedToTheClip() throws Exception {
        // A finished clip with the segment key the engine will report.
        repository.segmentFinalized(1, 1_000L, 2_000L, 4_096L, tmp.resolve("seg-1000.mkv"), true);
        assertTrue(repository.flush(Duration.ofSeconds(5)));

        Path snapshots = tmp.resolve("snapshots");
        AlertService service = new AlertService(snapshots, repository, bus);
        CountDownLatch presented = new CountDownLatch(1);
        List<AlertService.PresentedAlert> shown = new CopyOnWriteArrayList<>();
        service.onPresented(alert -> {
            shown.add(alert);
            presented.countDown();
        });
        List<ZoneAlert> published = new CopyOnWriteArrayList<>();
        bus.subscribe(ZoneAlert.class, published::add);

        ZoneDefinition zone = new ZoneDefinition(7, 1, "Front gate", 0.5, 0.5, 0.2, 0.3, true);
        long detectedUtcMs = System.currentTimeMillis();
        bus.publish(new IntrusionEvent(1, zone, detectedUtcMs, 1_000L, frame()));

        assertTrue(presented.await(5, TimeUnit.SECONDS), "alert was never presented");
        assertEquals(1, shown.size());
        assertEquals("Front gate", shown.get(0).zoneName());
        ZoneAlert alert = shown.get(0).alert();
        assertEquals(1, alert.cameraId());
        assertEquals(7, alert.zoneId());
        assertEquals(1_000L, alert.recordingStartUtcMs());
        assertTrue(Files.isRegularFile(alert.snapshotFile()), "snapshot PNG missing");
        BufferedImage png = ImageIO.read(alert.snapshotFile().toFile());
        assertNotNull(png);
        assertEquals(320, png.getWidth());
        assertEquals(180, png.getHeight());

        assertTrue(repository.flush(Duration.ofSeconds(5)));
        try (Connection connection = Database.open(database);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT camera_id, zone_id, recording_id, snapshot_path FROM alerts")) {
            assertTrue(rs.next(), "alerts row missing");
            assertEquals(1, rs.getLong("camera_id"));
            assertEquals(7, rs.getLong("zone_id"));
            assertTrue(rs.getLong("recording_id") > 0, "recording_id was not resolved from the clip");
            assertEquals(alert.snapshotFile().toAbsolutePath().toString(), rs.getString("snapshot_path"));
        }
        assertEquals(1, service.presentedCount());
        assertEquals(0, service.failedCount());
        service.close();
        // The bus copy is published after the UI callback; give the writer a moment.
        for (int i = 0; i < 50 && published.isEmpty(); i++) {
            Thread.sleep(20);
        }
        assertEquals(1, published.size(), "ZoneAlert was not published");
        assertEquals(alert.alertId(), published.get(0).alertId());
    }

    @Test
    void unlinkedAlertStoresNullRecordingId() throws Exception {
        Path snapshots = tmp.resolve("snapshots-unlinked");
        AlertService service = new AlertService(snapshots, repository, bus);
        CountDownLatch presented = new CountDownLatch(1);
        service.onPresented(alert -> presented.countDown());
        ZoneDefinition zone = new ZoneDefinition(3, 2, "Driveway", 0.1, 0.1, 0.2, 0.2, true);
        bus.publish(new IntrusionEvent(2, zone, System.currentTimeMillis(), 0L, frame()));
        assertTrue(presented.await(5, TimeUnit.SECONDS));
        assertTrue(repository.flush(Duration.ofSeconds(5)));
        try (Connection connection = Database.open(database);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT recording_id FROM alerts")) {
            assertTrue(rs.next());
            rs.getObject(1);
            assertNull(rs.getObject(1), "an unlinked alert must store a null recording_id");
        }
        service.close();
    }

    @Test
    void cooldownSuppressesASecondEntryIntoTheSameZone() throws Exception {
        Path snapshots = tmp.resolve("snapshots-cooldown");
        AlertService service = new AlertService(snapshots, repository, bus, cameraId -> 0L);
        List<AlertService.PresentedAlert> shown = new CopyOnWriteArrayList<>();
        service.onPresented(shown::add);
        ZoneDefinition frontGate = new ZoneDefinition(7, 1, "Front gate", 0.5, 0.5, 0.2, 0.3, true);
        ZoneDefinition driveway = new ZoneDefinition(8, 1, "Driveway", 0.1, 0.1, 0.2, 0.2, true);
        long now = System.currentTimeMillis();
        // Two confirmed tracks step into the same zone together: one popup, one row.
        bus.publish(new IntrusionEvent(1, frontGate, now, 0L, frame()));
        bus.publish(new IntrusionEvent(1, frontGate, now + 120L, 0L, frame()));
        // A different zone is a different notification.
        bus.publish(new IntrusionEvent(1, driveway, now + 240L, 0L, frame()));
        assertTrue(repository.flush(Duration.ofSeconds(5)));
        for (int i = 0; i < 100 && shown.size() < 2; i++) {
            Thread.sleep(20);
        }
        assertEquals(2, shown.size(), "expected one popup per zone, not per track");
        assertEquals(1, service.suppressedCount());
        try (Connection connection = Database.open(database);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM alerts")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1), "suppressed alerts must not reach the database");
        }
        service.close();
    }

    @Test
    void resolvesRecordingIdFromTheSupplierWhenEngineSendsZero() throws Exception {
        // The engine cannot know the DB id of an open segment; the app resolves it here.
        repository.segmentStarted(1, 5_000L, tmp.resolve("seg-5000.mkv"));
        assertTrue(repository.flush(Duration.ofSeconds(5)));
        Path snapshots = tmp.resolve("snapshots-fallback");
        AlertService service = new AlertService(snapshots, repository, bus, cameraId -> 5_000L);
        CountDownLatch presented = new CountDownLatch(1);
        service.onPresented(alert -> presented.countDown());
        ZoneDefinition zone = new ZoneDefinition(9, 1, "Yard", 0.2, 0.2, 0.3, 0.3, true);
        bus.publish(new IntrusionEvent(1, zone, System.currentTimeMillis(), 0L, frame()));
        assertTrue(presented.await(5, TimeUnit.SECONDS));
        assertTrue(repository.flush(Duration.ofSeconds(5)));
        try (Connection connection = Database.open(database);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT recording_id FROM alerts ORDER BY id DESC LIMIT 1")) {
            assertTrue(rs.next());
            assertEquals(1L, rs.getLong(1),
                    "the supplier should have resolved the recording of the open segment");
        }
        service.close();
    }

    private static BufferedImage frame() {
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x18, 0x20, 0x28));
        g.fillRect(0, 0, 320, 180);
        g.setColor(new Color(0xff, 0x6a, 0x4d));
        g.drawRect(120, 60, 60, 90);
        g.dispose();
        return image;
    }
}
