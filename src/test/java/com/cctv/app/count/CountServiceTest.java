package com.cctv.app.count;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cctv.app.db.Database;
import com.cctv.app.db.RecordingRepository;
import com.cctv.app.event.SimpleEventBus;
import com.cctv.contract.LineCrossingEvent;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CountServiceTest {

    @TempDir
    Path tmp;

    private Path database;
    private RecordingRepository repository;
    private SimpleEventBus bus;
    private CountService counts;

    @BeforeEach
    void setUp() throws Exception {
        database = tmp.resolve("counts.db");
        try (Connection ignored = Database.open(database)) {
            // schema only
        }
        repository = RecordingRepository.start(database);
        bus = new SimpleEventBus();
        counts = new CountService(database, repository, bus, ZoneId.of("UTC"));
    }

    @AfterEach
    void tearDown() {
        if (counts != null) {
            counts.close();
        }
        if (repository != null) {
            repository.close();
        }
    }

    @Test
    void crossingIsPersistedAndFoldedIntoTheDailySummary() throws Exception {
        long now = System.currentTimeMillis();
        bus.publish(new LineCrossingEvent(1, 7, 42, LineCrossingEvent.Direction.IN, now));
        bus.publish(new LineCrossingEvent(1, 7, 43, LineCrossingEvent.Direction.OUT, now + 1_000));
        bus.publish(new LineCrossingEvent(1, 7, 44, LineCrossingEvent.Direction.IN, now + 2_000));
        assertEquals(3, counts.eventsSeen());
        assertEquals(true, repository.flush(Duration.ofSeconds(5)));

        Map<Long, CountService.Totals> totals =
                counts.totalsForCamera(1, LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now),
                        ZoneId.of("UTC")));
        assertEquals(2, totals.get(7L).countIn());
        assertEquals(1, totals.get(7L).countOut());
        assertEquals(3, totals.get(7L).total());

        assertEquals(3, eventRows());
        assertEquals("2", summaryValue("count_in"));
        assertEquals("1", summaryValue("count_out"));
    }

    @Test
    void nearFieldGateIgnoresCrossingsOfSmallDistantTracks() throws Exception {
        String previous = System.getProperty("cctv.count.min.box.height");
        System.setProperty("cctv.count.min.box.height", "0.25");
        SimpleEventBus gatedBus = new SimpleEventBus();
        CountService gated = new CountService(database, repository, gatedBus, ZoneId.of("UTC"));
        try {
            long now = System.currentTimeMillis();
            // A distant pedestrian (10 % of frame height) walking along the far sidewalk.
            gatedBus.publish(new com.cctv.engine.TrackingUpdate(1, now,
                    List.of(track(1, 11, 0.10, true))));
            gatedBus.publish(new LineCrossingEvent(1, 7, 11, LineCrossingEvent.Direction.OUT, now));
            // A near pedestrian (40 % of frame height) crossing the same line.
            gatedBus.publish(new com.cctv.engine.TrackingUpdate(1, now + 500,
                    List.of(track(1, 12, 0.40, true))));
            gatedBus.publish(new LineCrossingEvent(1, 7, 12, LineCrossingEvent.Direction.IN, now + 500));
            assertTrue(repository.flush(Duration.ofSeconds(5)));
            assertEquals(1, eventRows(), "the distant track must not be counted");
            assertEquals(1, gated.suppressedFarField());
            assertEquals(2, gated.eventsSeen());
        } finally {
            gated.close();
            if (previous == null) {
                System.clearProperty("cctv.count.min.box.height");
            } else {
                System.setProperty("cctv.count.min.box.height", previous);
            }
        }
    }

    private static com.cctv.engine.TrackedObject track(long cameraId, long trackId, double height,
            boolean confirmed) {
        double x1 = 0.4;
        double x2 = 0.5;
        double y2 = 0.9;
        double y1 = y2 - height;
        long now = System.currentTimeMillis();
        return new com.cctv.engine.TrackedObject(cameraId, trackId, "person", 0.8f, x1, y1, x2, y2,
                now - 2_000, now, 3, 0, confirmed);
    }

    @Test
    void resetClearsEventsAndZeroesTheSummary() throws Exception {
        long now = System.currentTimeMillis();
        bus.publish(new LineCrossingEvent(2, 9, 1, LineCrossingEvent.Direction.IN, now));
        assertEquals(true, repository.flush(Duration.ofSeconds(5)));
        assertEquals(1, eventRows());

        counts.reset(2, LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneId.of("UTC")));
        assertEquals(true, repository.flush(Duration.ofSeconds(5)));
        assertEquals(0, eventRows());
        assertEquals("0", summaryValue("count_in"));
    }

    @Test
    void otherDaysAreNotCounted() throws Exception {
        long now = System.currentTimeMillis();
        bus.publish(new LineCrossingEvent(3, 5, 1, LineCrossingEvent.Direction.IN, now));
        assertEquals(true, repository.flush(Duration.ofSeconds(5)));

        Map<Long, CountService.Totals> yesterday =
                counts.totalsForCamera(3, LocalDate.now(ZoneId.of("UTC")).minusDays(1));
        assertEquals(0, yesterday.size());
    }

    private int eventRows() throws Exception {
        try (Connection connection = Database.open(database);
             PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM count_events");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String summaryValue(String column) throws Exception {
        try (Connection connection = Database.open(database);
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT " + column + " FROM count_summaries ORDER BY date DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? String.valueOf(rs.getInt(1)) : "missing";
        }
    }
}
