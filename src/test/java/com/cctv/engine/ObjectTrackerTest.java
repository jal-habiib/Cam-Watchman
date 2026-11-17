package com.cctv.engine;

import com.cctv.contract.Detection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ObjectTrackerTest {
    private static Detection person(long camera, long time, double x) {
        return new Detection(camera, "person", 0.9f, x, 0.1, x + 0.2, 0.8, time);
    }

    @Test
    void sameObjectKeepsIdentityAcrossSmallMovementAndConfirmsOnSecondHit() {
        ObjectTracker tracker = new ObjectTracker(1);
        TrackedObject first = tracker.update(1_000, List.of(person(1, 1_000, 0.10))).get(0);
        TrackedObject second = tracker.update(1_100, List.of(person(1, 1_100, 0.11))).get(0);
        assertEquals(first.trackId(), second.trackId());
        assertFalse(first.confirmed());
        assertTrue(second.confirmed());
        assertEquals(2, second.hits());
        assertEquals(1_000, second.firstSeenUtcMs());
        assertEquals(1_100, second.lastSeenUtcMs());
    }

    @Test
    void separateObjectsGetDifferentIdsAndClassNeverCrossMatches() {
        ObjectTracker tracker = new ObjectTracker(1);
        List<TrackedObject> first = tracker.update(1_000,
                List.of(person(1, 1_000, 0.05), person(1, 1_000, 0.65)));
        assertEquals(2, first.size());
        assertNotEquals(first.get(0).trackId(), first.get(1).trackId());

        Detection car = new Detection(1, "car", 0.9f, 0.05, 0.1, 0.25, 0.8, 1_100);
        List<TrackedObject> next = tracker.update(1_100, List.of(car));
        assertEquals(3, next.size(), "class mismatch creates a new identity; old tracks age naturally");
    }

    @Test
    void missedFramesAreRetainedBrieflyThenExpired() {
        ObjectTracker tracker = new ObjectTracker(1, 0.25f, 1, 10_000, 2);
        tracker.update(1_000, List.of(person(1, 1_000, 0.1)));
        assertEquals(1, tracker.update(1_100, List.of()).size());
        assertTrue(tracker.update(1_200, List.of()).isEmpty());
    }

    @Test
    void timestampMustBeMonotonicAndDetectionsMustMatchFrame() {
        ObjectTracker tracker = new ObjectTracker(1);
        tracker.update(1_000, List.of(person(1, 1_000, 0.1)));
        assertThrows(IllegalArgumentException.class, () -> tracker.update(999, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> tracker.update(1_100, List.of(person(2, 1_100, 0.1))));
    }

    @Test
    void centroidAssociationKeepsIdentityWhenIouFallsBelowOldThreshold() {
        ObjectTracker tracker = new ObjectTracker(1, 0.18f, 12, 2_500, 2);
        TrackedObject first = tracker.update(1_000, List.of(person(1, 1_000, 0.10))).get(0);
        TrackedObject moved = tracker.update(1_250, List.of(person(1, 1_250, 0.25))).get(0);

        assertEquals(first.trackId(), moved.trackId(),
                "nearby same-class object should keep identity despite low box overlap");
        assertEquals(2, moved.hits());
        assertTrue(moved.confirmed());
    }

    @Test
    void constrainedTrackGetsItsOnlyCandidateBeforeFlexibleTrack() {
        ObjectTracker tracker = new ObjectTracker(1, 0.18f, 12, 2_500, 2);
        List<TrackedObject> first = tracker.update(1_000,
                List.of(person(1, 1_000, 0.10), person(1, 1_000, 0.25)));
        long leftTrack = first.get(0).trackId();
        long rightTrack = first.get(1).trackId();

        // The left track can only reach detection X; the right track can reach X or Y.
        // Plain nearest-pair greedy matching consumes X for the right track and creates
        // a replacement ID for the left. Constrained-first ordering preserves both IDs.
        List<TrackedObject> next = tracker.update(1_100,
                List.of(person(1, 1_100, 0.18), person(1, 1_100, 0.35)));

        assertEquals(2, next.size());
        assertEquals(leftTrack, next.get(0).trackId());
        assertEquals(rightTrack, next.get(1).trackId());
        assertTrue(next.stream().allMatch(TrackedObject::confirmed));
    }

    @Test
    void staleTracksExpireByElapsedTimeEvenBeforeMissedFrameLimit() {
        ObjectTracker tracker = new ObjectTracker(1, 0.18f, 100, 1_000, 2);
        tracker.update(1_000, List.of(person(1, 1_000, 0.10)));
        assertTrue(tracker.update(2_001, List.of()).isEmpty());
    }

    @Test
    void equalValuedDetectionsAreMatchedByFrameIndexNotRecordEquality() {
        ObjectTracker tracker = new ObjectTracker(1);
        Detection first = person(1, 1_000, 0.2);
        List<TrackedObject> initial = tracker.update(1_000, List.of(first, first));
        assertEquals(2, initial.size());
        assertNotEquals(initial.get(0).trackId(), initial.get(1).trackId());

        Detection next = person(1, 1_100, 0.2);
        List<TrackedObject> updated = tracker.update(1_100, List.of(next, next));
        assertEquals(2, updated.size());
        assertEquals(initial.get(0).trackId(), updated.get(0).trackId());
        assertEquals(initial.get(1).trackId(), updated.get(1).trackId());
        assertTrue(updated.stream().allMatch(TrackedObject::confirmed));
    }

    @Test
    void emptyFirstFrameAndResetAreSafe() {
        ObjectTracker tracker = new ObjectTracker(1);
        assertTrue(tracker.update(1_000, List.of()).isEmpty());
        assertEquals(1, tracker.update(1_100, List.of(person(1, 1_100, 0.1))).get(0).trackId());
        tracker.reset();
        assertEquals(1, tracker.update(2_000, List.of(person(1, 2_000, 0.1))).get(0).trackId());
    }
}
