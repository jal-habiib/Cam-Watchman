package com.cctv.app.alert;

import com.cctv.app.db.ZoneStore;
import com.cctv.app.event.SimpleEventBus;
import com.cctv.contract.Detection;
import com.cctv.contract.EventBus;
import com.cctv.contract.IntrusionEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Plan 5b: feet point, two consecutive detections, 60 s cooldown per zone. */
class ZoneConfirmerTest {

    private static final long CAM = 2;
    private static final ZoneStore.Zone GATE = new ZoneStore.Zone(1, CAM, "Front gate", 0.2, 0.2, 0.4, 0.5, true);
    private static final ZoneStore.Zone DISABLED = new ZoneStore.Zone(2, CAM, "Disabled", 0.2, 0.2, 0.4, 0.5, false);
    private static final ZoneStore.Zone ELSEWHERE = new ZoneStore.Zone(3, CAM, "Elsewhere", 0.8, 0.8, 0.2, 0.2, true);
    private static final BufferedImage FRAME = new BufferedImage(64, 36, BufferedImage.TYPE_INT_ARGB);

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final List<IntrusionEvent> events = new ArrayList<>();
    private final EventBus bus = new SimpleEventBus();

    private ZoneConfirmer confirmer(ZoneStore.Zone... zones) {
        List<ZoneStore.Zone> available = List.of(zones);
        ZoneConfirmer result = new ZoneConfirmer(cameraId -> available, cameraId -> FRAME, id -> 0L, bus,
                now::get);
        bus.subscribe(IntrusionEvent.class, events::add);
        return result;
    }

    /** A person box whose bottom-centre (the feet point) is at (feetX, feetY). */
    private static Detection person(double feetX, double feetY) {
        double x1 = Math.max(0.0, Math.min(0.95, feetX - 0.05));
        double x2 = Math.min(1.0, x1 + 0.1);
        double y1 = Math.max(0.0, feetY - 0.2);
        return new Detection(CAM, "person", 0.9f, x1, y1, x2, feetY, 1_000_000L);
    }

    @Test
    void twoConsecutiveDetectionsFireOneEvent() {
        try (ZoneConfirmer confirmer = confirmer(GATE)) {
            confirmer.onDetection(person(0.4, 0.4));
            assertEquals(0, events.size(), "one detection is not enough");
            now.addAndGet(1_000);
            confirmer.onDetection(person(0.4, 0.4));
            assertEquals(1, events.size());
            assertEquals(1, events.get(0).zone().id());
            assertEquals(CAM, events.get(0).cameraId());
        }
    }

    @Test
    void sixtySecondCooldownBlocksTheNextEntry() {
        try (ZoneConfirmer confirmer = confirmer(GATE)) {
            confirmer.onDetection(person(0.4, 0.4));
            now.addAndGet(1_000);
            confirmer.onDetection(person(0.4, 0.4));
            now.addAndGet(1_000);
            confirmer.onDetection(person(0.4, 0.4));
            assertEquals(1, events.size(), "still inside the 60 s cooldown");

            now.addAndGet(60_000);
            confirmer.onDetection(person(0.4, 0.4));
            now.addAndGet(1_000);
            confirmer.onDetection(person(0.4, 0.4));
            assertEquals(2, events.size(), "after the cooldown a new entry fires");
        }
    }

    @Test
    void gapLongerThanTheWindowResetsTheStreak() {
        try (ZoneConfirmer confirmer = confirmer(GATE)) {
            confirmer.onDetection(person(0.4, 0.4));
            now.addAndGet(ZoneConfirmer.CONSECUTIVE_WINDOW_MS + 1);
            confirmer.onDetection(person(0.4, 0.4));
            assertEquals(0, events.size(), "two isolated detections never confirm");
            now.addAndGet(500);
            confirmer.onDetection(person(0.4, 0.4));
            assertEquals(1, events.size());
        }
    }

    @Test
    void feetOutsideTheZoneAndInactiveZonesAreIgnored() {
        try (ZoneConfirmer confirmer = confirmer(GATE, DISABLED, ELSEWHERE)) {
            confirmer.onDetection(person(0.1, 0.1));   // outside GATE
            now.addAndGet(1_000);
            confirmer.onDetection(person(0.1, 0.1));
            confirmer.onDetection(person(0.4, 0.4));   // inside DISABLED only (inactive)
            assertEquals(0, events.size());
        }
    }

    @Test
    void nonPersonClassesNeverTrigger() {
        try (ZoneConfirmer confirmer = confirmer(GATE)) {
            Detection car = new Detection(CAM, "car", 0.9f, 0.35, 0.2, 0.45, 0.4, 1_000_000L);
            confirmer.onDetection(car);
            now.addAndGet(1_000);
            confirmer.onDetection(car);
            assertEquals(0, events.size());
        }
    }
}
