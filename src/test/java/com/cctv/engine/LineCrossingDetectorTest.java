package com.cctv.engine;

import com.cctv.contract.CountLine;
import com.cctv.contract.LineCrossingEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LineCrossingDetectorTest {
    private static final CountLine VERTICAL = new CountLine(1, 1, "Door",
            0.5, 0.1, 0.5, 0.9, true);

    private static TrackedObject person(long id, double centerX, boolean confirmed) {
        return new TrackedObject(1, id, "person", 0.95f, centerX - 0.05, 0.2,
                centerX + 0.05, 0.8, 1_000, 1_000, confirmed ? 2 : 1, 0, confirmed);
    }

    @Test
    void crossingNegativeToPositiveEmitsInOnce() {
        LineCrossingDetector detector = new LineCrossingDetector(1, 0.01);
        assertTrue(detector.update(1_000, List.of(VERTICAL), List.of(person(7, 0.40, true))).isEmpty());
        List<LineCrossingEvent> events = detector.update(1_100, List.of(VERTICAL),
                List.of(person(7, 0.60, true)));
        assertEquals(1, events.size());
        assertEquals(LineCrossingEvent.Direction.OUT, events.get(0).direction());
        assertEquals(7, events.get(0).trackId());
    }

    @Test
    void reverseCrossingEmitsOutAndJitterInsideDeadbandDoesNotDuplicate() {
        LineCrossingDetector detector = new LineCrossingDetector(1, 0.01);
        detector.update(1_000, List.of(VERTICAL), List.of(person(2, 0.40, true)));
        assertEquals(1, detector.update(1_100, List.of(VERTICAL),
                List.of(person(2, 0.60, true))).size());
        assertTrue(detector.update(1_200, List.of(VERTICAL), List.of(person(2, 0.50, true))).isEmpty());
        assertTrue(detector.update(1_300, List.of(VERTICAL), List.of(person(2, 0.51, true))).isEmpty());
        List<LineCrossingEvent> reverse = detector.update(1_400, List.of(VERTICAL),
                List.of(person(2, 0.40, true)));
        assertEquals(1, reverse.size());
        assertEquals(LineCrossingEvent.Direction.IN, reverse.get(0).direction());
    }

    @Test
    void onlyConfirmedTracksAndActiveLinesCanCount() {
        LineCrossingDetector detector = new LineCrossingDetector(1, 0.01);
        CountLine inactive = new CountLine(2, 1, "Disabled", 0.5, 0.1, 0.5, 0.9, false);
        detector.update(1_000, List.of(VERTICAL, inactive), List.of(person(4, 0.4, false)));
        assertTrue(detector.update(1_100, List.of(inactive), List.of(person(4, 0.6, true))).isEmpty());
    }

    @Test
    void trackExpiryAndLineRemovalClearPreviousSide() {
        LineCrossingDetector detector = new LineCrossingDetector(1, 0.01);
        detector.update(1_000, List.of(VERTICAL), List.of(person(5, 0.4, true)));
        detector.update(1_100, List.of(), List.of());
        assertTrue(detector.update(1_200, List.of(VERTICAL),
                List.of(person(5, 0.6, true))).isEmpty(), "new observation must establish a side first");
    }

    @Test
    void rejectsForeignCameraAndNonMonotonicFrames() {
        LineCrossingDetector detector = new LineCrossingDetector(1);
        assertThrows(IllegalArgumentException.class, () -> detector.update(1_000,
                List.of(new CountLine(3, 2, "Other", 0.1, 0.1, 0.9, 0.9, true)), List.of()));
        detector.update(1_000, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> detector.update(999, List.of(), List.of()));
    }
}