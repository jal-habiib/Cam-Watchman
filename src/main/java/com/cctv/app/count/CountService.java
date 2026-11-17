package com.cctv.app.count;

import com.cctv.app.db.Database;
import com.cctv.app.db.RecordingRepository;
import com.cctv.contract.EventBus;
import com.cctv.contract.LineCrossingEvent;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 7b/7c: consumes {@link LineCrossingEvent}s from the engine, persists them through the
 * single writer, and answers "today's counts" for the UI.
 *
 * <p>The bus listener only enqueues (contract boundary rule): no SQL and no file I/O happens on the
 * engine's inference thread.
 */
public final class CountService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CountService.class);

    /** One camera+line+day's totals. */
    public record Totals(long countIn, long countOut) {

        public long total() {
            return countIn + countOut;
        }
    }

    private final Path dbFile;
    private final RecordingRepository repository;
    private final EventBus.Subscription subscription;
    private final EventBus.Subscription trackingSubscription;
    private final ZoneId zone;
    private final AtomicLong eventsSeen = new AtomicLong();
    private final AtomicLong suppressedFarField = new AtomicLong();
    /**
     * Optional near-field gate for real footage: a line that spans the whole frame also counts
     * distant pedestrians the track IDs churn on. When set ({@code -Dcctv.count.min.box.height}),
     * a crossing whose track box is shorter than this normalized height is ignored.
     */
    private final double minBoxHeight = readMinBoxHeight();
    /** Last known box height per camera:track, fed by TrackingUpdate. */
    private final Map<String, Double> trackHeights = new java.util.concurrent.ConcurrentHashMap<>();

    public CountService(Path dbFile, RecordingRepository repository, EventBus bus) {
        this(dbFile, repository, bus, ZoneId.systemDefault());
    }

    public CountService(Path dbFile, RecordingRepository repository, EventBus bus, ZoneId zone) {
        this.dbFile = dbFile;
        this.repository = repository;
        this.zone = zone;
        this.subscription = bus.subscribe(LineCrossingEvent.class, this::onCrossing);
        this.trackingSubscription = bus.subscribe(com.cctv.engine.TrackingUpdate.class,
                this::onTracks);
        log.info("count service ready (day boundary {}{})", zone,
                minBoxHeight > 0 ? ", near-field gate " + minBoxHeight : "");
    }

    private static double readMinBoxHeight() {
        String raw = System.getProperty("cctv.count.min.box.height");
        if (raw == null || raw.isBlank()) {
            return 0.0;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            return Math.max(0.0, Math.min(1.0, value));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private void onTracks(com.cctv.engine.TrackingUpdate update) {
        String prefix = update.cameraId() + ":";
        trackHeights.keySet().removeIf(key -> key.startsWith(prefix));
        for (com.cctv.engine.TrackedObject track : update.tracks()) {
            trackHeights.put(prefix + track.trackId(), track.y2() - track.y1());
        }
    }

    private void onCrossing(LineCrossingEvent event) {
        eventsSeen.incrementAndGet();
        if (minBoxHeight > 0.0) {
            Double height = trackHeights.get(event.cameraId() + ":" + event.trackId());
            if (height != null && height < minBoxHeight) {
                suppressedFarField.incrementAndGet();
                log.debug("count skipped: camera {} track {} box {:.2f} < {}", event.cameraId(),
                        event.trackId(), height, minBoxHeight);
                return;
            }
        }
        LocalDate day = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(event.crossedUtcMs()),
                zone);
        repository.crossing(event.cameraId(), event.lineId(), event.trackId(),
                event.direction().name(), event.crossedUtcMs(), dayKey(day));
        log.debug("count crossing: camera {} line {} track {} {}", event.cameraId(),
                event.lineId(), event.trackId(), event.direction());
    }

    /** Totals per line for one camera and one local day; missing lines are simply absent. */
    public Map<Long, Totals> totalsForCamera(long cameraId, LocalDate day) throws SQLException {
        long from = startOfDay(day);
        long to = startOfDay(day.plusDays(1)) - 1;
        Map<Long, Totals> totals = new HashMap<>();
        try (Connection connection = Database.open(dbFile);
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT line_id,"
                             + " SUM(CASE WHEN direction = 'IN' THEN 1 ELSE 0 END),"
                             + " SUM(CASE WHEN direction = 'OUT' THEN 1 ELSE 0 END)"
                             + " FROM count_events WHERE camera_id = ? AND crossed_utc BETWEEN ? AND ?"
                             + " GROUP BY line_id")) {
            ps.setLong(1, cameraId);
            ps.setLong(2, from);
            ps.setLong(3, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    totals.put(rs.getLong(1), new Totals(rs.getLong(2), rs.getLong(3)));
                }
            }
        }
        return totals;
    }

    /** Clears one camera's events for the day and zeroes its summary row. */
    public void reset(long cameraId, LocalDate day) {
        repository.resetCounters(cameraId, startOfDay(day), startOfDay(day.plusDays(1)) - 1,
                dayKey(day));
        log.info("count counters reset: camera {} day {}", cameraId, dayKey(day));
    }

    public long eventsSeen() {
        return eventsSeen.get();
    }

    /** Crossings ignored by the near-field gate. */
    public long suppressedFarField() {
        return suppressedFarField.get();
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    private long startOfDay(LocalDate day) {
        return day.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    static String dayKey(LocalDate day) {
        return day.toString();
    }

    @Override
    public void close() {
        subscription.close();
        trackingSubscription.close();
    }
}
