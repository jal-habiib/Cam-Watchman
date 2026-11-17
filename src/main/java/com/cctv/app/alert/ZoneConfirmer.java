package com.cctv.app.alert;

import com.cctv.app.db.ZoneStore;
import com.cctv.contract.Detection;
import com.cctv.contract.EventBus;
import com.cctv.contract.IntrusionEvent;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns raw person detections into intrusion events (plan 5b: feet point, two consecutive
 * detections, 60 s cooldown per zone).
 *
 * <p>Contract v0.2 assigns zone confirmation to the engine. This class exists while the engine has
 * no zone source: it is the app-side placeholder so the alert path can be exercised end to end.
 * Disable it with {@code -Dcctv.zone.confirm.app=false} once the engine publishes its own
 * {@link IntrusionEvent}s, so an event is never produced twice.
 *
 * <p>Everything here runs on the caller's thread (the engine detection callback), so it must stay
 * cheap: map lookups and at most one bus publish per confirmed entry.
 */
public final class ZoneConfirmer implements AutoCloseable {

    /** Detections further apart than this are not treated as consecutive (at 1 fps, two frames = 1 s). */
    static final long CONSECUTIVE_WINDOW_MS = 5_000L;
    /** Plan 5b: at most one alert per zone per entry within 60 s. */
    static final long COOLDOWN_MS = 60_000L;
    /** Plan 5b: confirmation needs two consecutive detections, not one. */
    static final int REQUIRED_CONSECUTIVE = 2;

    private static final Logger log = LoggerFactory.getLogger(ZoneConfirmer.class);

    /** Active zones for one camera; the app passes {@code zoneStore::forCamera}. */
    public interface ZoneSource {
        List<ZoneStore.Zone> zonesFor(long cameraId);
    }

    /** Frame for the alert snapshot; returns null when the display has no frame yet. */
    public interface Snapshot {
        BufferedImage capture(long cameraId);
    }

    private record Window(long lastUtcMs, int consecutive) {
    }

    private final EventBus bus;
    private final ZoneSource zones;
    private final Snapshot snapshot;
    private final LongUnaryOperator segmentStartForCamera;
    private final LongSupplier nowMs;
    private final Map<String, Window> windows = new HashMap<>();
    private final Map<String, Long> firedAt = new HashMap<>();
    private final EventBus.Subscription subscription;
    private long firedCount;

    public ZoneConfirmer(ZoneSource zones, Snapshot snapshot,
                         LongUnaryOperator segmentStartForCamera, EventBus bus, LongSupplier nowMs) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.zones = Objects.requireNonNull(zones, "zones");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.segmentStartForCamera = segmentStartForCamera == null ? cameraId -> 0L : segmentStartForCamera;
        this.nowMs = nowMs == null ? System::currentTimeMillis : nowMs;
        this.subscription = bus.subscribe(Detection.class, this::onDetection);
    }

    /** One detection: update the per-zone streak and publish when the entry is confirmed. */
    void onDetection(Detection detection) {
        if (!"person".equals(detection.objectClass())) {
            return;
        }
        long now = nowMs.getAsLong();
        List<ZoneStore.Zone> active = zones.zonesFor(detection.cameraId());
        for (ZoneStore.Zone zone : active) {
            if (!zone.active() || !zone.contains(detection.feetX(), detection.feetY())) {
                continue;
            }
            String key = detection.cameraId() + ":" + zone.id();
            Window previous = windows.get(key);
            int consecutive = previous != null && now - previous.lastUtcMs() <= CONSECUTIVE_WINDOW_MS
                    ? previous.consecutive() + 1 : 1;
            windows.put(key, new Window(now, consecutive));
            Long lastFired = firedAt.get(key);
            if (consecutive < REQUIRED_CONSECUTIVE) {
                continue;
            }
            if (lastFired != null && now - lastFired < COOLDOWN_MS) {
                continue;
            }
            BufferedImage image = snapshot.capture(detection.cameraId());
            if (image == null) {
                log.debug("zone entry confirmed but no frame is available yet (camera {} zone {})",
                        detection.cameraId(), zone.id());
                continue;
            }
            firedAt.put(key, now);
            firedCount++;
            bus.publish(new IntrusionEvent(detection.cameraId(), zone.toDefinition(), now,
                    segmentStartForCamera.applyAsLong(detection.cameraId()), image));
            log.info("zone entry: camera {} zone {} ({}) after {} consecutive detection(s)",
                    detection.cameraId(), zone.id(), zone.name(), consecutive);
        }
    }

    public long firedCount() {
        return firedCount;
    }

    @Override
    public void close() {
        subscription.close();
    }
}
