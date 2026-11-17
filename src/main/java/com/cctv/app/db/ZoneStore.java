package com.cctv.app.db;

import com.cctv.contract.ZoneDefinition;
import com.cctv.contract.ZoneSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 5a: zone storage. Reads go straight to the database (WAL readers never block the writer);
 * writes are handed to {@link RecordingRepository}'s single writer thread, so the one-writer rule in
 * the plan holds for zones as well as clips.
 *
 * <p>Rectangles are normalized to the frame (0..1 on both axes) and validated on the way in, because
 * a zone that does not fit the frame is a silent false-alert generator.
 */
public final class ZoneStore implements ZoneSource, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ZoneStore.class);

    /** A stored zone: database id + normalized rectangle + the name shown in alerts. */
    public record Zone(long id, long cameraId, String name, double x, double y, double w, double h,
                       boolean active) {

        public Zone {
            if (id < 0) {
                throw new IllegalArgumentException("zone id must not be negative");
            }
            if (cameraId <= 0) {
                throw new IllegalArgumentException("cameraId must be positive");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("zone name is required");
            }
            requireUnit("x", x);
            requireUnit("y", y);
            requireUnit("w", w);
            requireUnit("h", h);
            if (w <= 0 || h <= 0) {
                throw new IllegalArgumentException("zone width and height must be positive");
            }
            // allow a hair of floating-point slack at the right/bottom edge
            if (x + w > 1.000_001 || y + h > 1.000_001) {
                throw new IllegalArgumentException("zone must fit inside the frame");
            }
        }

        private static void requireUnit(String name, double value) {
            if (!(value >= 0.0) || value > 1.000_001) {
                throw new IllegalArgumentException(name + " must be in 0..1, got " + value);
            }
        }

        public double right() {
            return Math.min(1.0, x + w);
        }

        public double bottom() {
            return Math.min(1.0, y + h);
        }

        public boolean contains(double px, double py) {
            return active && px >= x && px <= right() && py >= y && py <= bottom();
        }

        public ZoneDefinition toDefinition() {
            return new ZoneDefinition(id, cameraId, name, x, y, w, h, active);
        }

        public ZoneDraft toDraft() {
            return new ZoneDraft(name, x, y, w, h, active);
        }
    }

    private record Cached(List<ZoneDefinition> zones, long loadedAtMs) {
    }

    private final Path databaseFile;
    private final RecordingRepository repository;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();
    private volatile long ttlMs = 2_000;

    public ZoneStore(Path databaseFile, RecordingRepository repository) {
        this.databaseFile = databaseFile;
        this.repository = repository;
    }

    /** Test hook: shorten the cache TTL to make polling deterministic. */
    void cacheTtlMs(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /**
     * Engine-facing view: active zones only, cached for a short TTL because the engine calls this
     * once per inference frame and must not open a database connection on that thread.
     */
    @Override
    public List<ZoneDefinition> activeZones(long cameraId) {
        Cached cached = cache.get(cameraId);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAtMs() <= ttlMs) {
            return cached.zones();
        }
        List<ZoneDefinition> zones = forCamera(cameraId).stream()
                .filter(Zone::active)
                .map(Zone::toDefinition)
                .toList();
        cache.put(cameraId, new Cached(zones, now));
        return zones;
    }

    public void invalidate(long cameraId) {
        cache.remove(cameraId);
    }

    /** Zones of one camera, oldest first. */
    public List<Zone> forCamera(long cameraId) {
        String sql = "SELECT id, camera_id, name, x, y, w, h, active FROM zones "
                + "WHERE camera_id = ? ORDER BY id";
        return query(sql, cameraId);
    }

    public List<Zone> all() {
        return query("SELECT id, camera_id, name, x, y, w, h, active FROM zones ORDER BY id", null);
    }

    public Optional<Zone> byId(long zoneId) {
        List<Zone> zones = query(
                "SELECT id, camera_id, name, x, y, w, h, active FROM zones WHERE id = ?", zoneId);
        return zones.isEmpty() ? Optional.empty() : Optional.of(zones.get(0));
    }

    /**
     * Replaces every zone of one camera with {@code drafts} (delete + insert in one transaction on
     * the writer thread). Ids are reassigned, so callers reload after flushing.
     */
    public void replaceForCamera(long cameraId, List<ZoneDraft> drafts) {
        for (ZoneDraft draft : drafts) {
            // validate before the queue so the UI gets the error synchronously
            new Zone(0, cameraId, draft.name(), draft.x(), draft.y(), draft.w(), draft.h(),
                    draft.active());
        }
        repository.replaceZones(cameraId, List.copyOf(drafts));
        invalidate(cameraId);
        log.info("zone save queued: camera {} -> {} zone(s)", cameraId, drafts.size());
    }

    public void setActive(long zoneId, boolean active) {
        repository.setZoneActive(zoneId, active);
        cache.clear();
    }

    public void delete(long zoneId) {
        repository.deleteZone(zoneId);
        cache.clear();
    }

    private List<Zone> query(String sql, Long id) {
        List<Zone> zones = new ArrayList<>();
        try (Connection connection = Database.open(databaseFile);
             PreparedStatement ps = connection.prepareStatement(sql)) {
            if (id != null) {
                ps.setLong(1, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    zones.add(new Zone(rs.getLong("id"), rs.getLong("camera_id"),
                            rs.getString("name"), rs.getDouble("x"), rs.getDouble("y"),
                            rs.getDouble("w"), rs.getDouble("h"), rs.getInt("active") != 0));
                }
            }
        } catch (SQLException | IllegalArgumentException e) {
            log.warn("cannot read zones ({}): {}", sql, e.toString());
        }
        return zones;
    }

    @Override
    public void close() {
        cache.clear();
    }
}
