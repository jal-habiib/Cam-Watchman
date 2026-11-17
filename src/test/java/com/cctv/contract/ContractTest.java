package com.cctv.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards the v0.2 boundary rules that both planes depend on. */
class ContractTest {

    @Test
    void zoneContainsItsFeetPointsInclusiveEdges() {
        ZoneDefinition zone = new ZoneDefinition(1, 2, "door", 0.10, 0.20, 0.30, 0.40, true);
        assertTrue(zone.contains(0.25, 0.40));
        assertTrue(zone.contains(0.10, 0.20), "top-left edge is inside");
        assertFalse(zone.contains(0.09, 0.40));
        assertFalse(zone.contains(0.41, 0.40));
        assertTrue(zone.withActive(false).active() == false);
    }

    @Test
    void detectionFeetPointIsBottomCentre() {
        Detection detection = new Detection(1, "person", 0.9f, 0.2, 0.1, 0.6, 0.9, 1_700_000_000_000L);
        assertEquals(0.4, detection.feetX(), 1e-9);
        assertEquals(0.9, detection.feetY(), 1e-9);
        assertTrue(detection.height() > 0);
    }

    @Test
    void rejectsZonesOutsideTheFrame() {
        assertThrows(IllegalArgumentException.class,
                () -> new ZoneDefinition(1, 1, "bad", 0.8, 0.1, 0.3, 0.2, true));
        assertThrows(IllegalArgumentException.class,
                () -> new ZoneDefinition(1, 1, "bad", -0.01, 0.1, 0.2, 0.2, true));
        assertThrows(IllegalArgumentException.class,
                () -> new ZoneDefinition(1, 1, "flat", 0.1, 0.1, 0.0, 0.2, true));
    }

    @Test
    void segmentEventsCarryNoDatabaseId() {
        SegmentStarted started = new SegmentStarted(1, 1_700_000_000_000L, Path.of("data/rec/cam1.mkv"));
        assertEquals(1, started.cameraId());
        assertTrue(started.file().isAbsolute(), "paths are normalized to absolute");
        ClipFinalized finalized = new ClipFinalized(1, started.startUtcMs(), started.startUtcMs() + 15_000,
                4_096, started.file(), true);
        assertEquals(15_000, finalized.endUtcMs() - finalized.startUtcMs());
        assertThrows(IllegalArgumentException.class,
                () -> new ClipFinalized(1, 10, 9, 1, Path.of("x.mkv"), false));
    }

    @Test
    void intrusionEventHandsOutAnIndependentSnapshotCopy() {
        BufferedImage image = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
        ZoneDefinition zone = new ZoneDefinition(1, 1, "gate", 0.1, 0.1, 0.2, 0.2, true);
        IntrusionEvent event = new IntrusionEvent(1, zone, 1_700_000_000_000L, 0, image);
        BufferedImage copy = event.snapshotCopy();
        copy.setRGB(0, 0, 0xFFFFFF);
        assertFalse(event.snapshot().getRGB(0, 0) == 0xFFFFFF, "the engine frame was not mutated");
    }

    @Test
    void streamStatusRejectsImpossibleState() {
        assertThrows(IllegalArgumentException.class,
                () -> new StreamStatusEvent(0, StreamState.ONLINE, 1, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> new StreamStatusEvent(1, null, 1, "x"));
        assertEquals("", StreamStatusEvent.now(1, StreamState.OFFLINE, null).detail());
    }

    @Test
    void zoneAlertNeedsARealUuid() {
        assertThrows(IllegalArgumentException.class,
                () -> new ZoneAlert("not-a-uuid", 1, 1, 0, Path.of("s.png"), 1));
        ZoneAlert alert = ZoneAlert.create(1, 2, 3, Path.of("s.png"), 1_700_000_000_000L);
        assertTrue(alert.alertId().length() == 36);
    }
}
