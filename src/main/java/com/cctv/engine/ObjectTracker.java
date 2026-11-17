package com.cctv.engine;

import com.cctv.contract.Detection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight camera-local centroid tracker. Constrained-first greedy one-to-one,
 * class-aware matching tolerates box-size changes and low-overlap motion between CPU inference frames.
 *
 * <p>Call once per processed frame, including frames with zero detections. Track IDs are
 * monotonically increasing for this tracker instance and never shared across cameras.
 */
public final class ObjectTracker {
    /** Maximum normalized center-to-center distance for a candidate association. */
    public static final float DEFAULT_MATCH_DISTANCE = 0.18f;
    public static final int DEFAULT_MAX_MISSED_FRAMES = 12;
    public static final long DEFAULT_MAX_AGE_MS = 2_500L;
    public static final int DEFAULT_CONFIRM_HITS = 2;

    private static final class State {
        final long id;
        final long firstSeen;
        final String objectClass;
        double x1, y1, x2, y2;
        float confidence;
        long lastSeen;
        int hits = 1;
        int missed;

        State(long id, Detection d) {
            this.id = id;
            this.firstSeen = d.frameUtcMs();
            this.objectClass = d.objectClass();
            this.x1 = d.x1(); this.y1 = d.y1(); this.x2 = d.x2(); this.y2 = d.y2();
            this.confidence = d.confidence();
            this.lastSeen = d.frameUtcMs();
        }

        void update(Detection d) {
            x1 = d.x1(); y1 = d.y1(); x2 = d.x2(); y2 = d.y2();
            confidence = d.confidence();
            lastSeen = d.frameUtcMs();
            hits++;
            missed = 0;
        }

        double centerX() { return (x1 + x2) / 2d; }
        double centerY() { return (y1 + y2) / 2d; }

        TrackedObject snapshot(long cameraId, int confirmHits) {
            return new TrackedObject(cameraId, id, objectClass, confidence, x1, y1, x2, y2,
                    firstSeen, lastSeen, hits, missed, hits >= confirmHits);
        }
    }

    private record Pair(State track, Detection detection, double distance, int detectionIndex) { }

    private final long cameraId;
    private final float matchDistance;
    private final int maxMissedFrames;
    private final long maxAgeMs;
    private final int confirmHits;
    private final List<State> active = new ArrayList<>();
    private long nextTrackId = 1;
    private long lastFrameUtcMs;

    public ObjectTracker(long cameraId) {
        this(cameraId, DEFAULT_MATCH_DISTANCE, DEFAULT_MAX_MISSED_FRAMES,
                DEFAULT_MAX_AGE_MS, DEFAULT_CONFIRM_HITS);
    }

    /**
     * @param matchDistance maximum normalized Euclidean distance between box centers
     *                      (kept as the second constructor argument for source compatibility)
     */
    public ObjectTracker(long cameraId, float matchDistance, int maxMissedFrames,
                         long maxAgeMs, int confirmHits) {
        if (cameraId <= 0) throw new IllegalArgumentException("cameraId must be positive");
        if (!Float.isFinite(matchDistance) || matchDistance < 0f || matchDistance > 1f)
            throw new IllegalArgumentException("matchDistance must be in [0,1]");
        if (maxMissedFrames < 0 || maxAgeMs < 1 || confirmHits < 1)
            throw new IllegalArgumentException("invalid tracker lifecycle settings");
        this.cameraId = cameraId;
        this.matchDistance = matchDistance;
        this.maxMissedFrames = maxMissedFrames;
        this.maxAgeMs = maxAgeMs;
        this.confirmHits = confirmHits;
    }

    /** Updates all tracks from one camera frame and returns immutable active snapshots. */
    public synchronized List<TrackedObject> update(long frameUtcMs, List<Detection> detections) {
        if (frameUtcMs <= 0 || frameUtcMs < lastFrameUtcMs)
            throw new IllegalArgumentException("frame timestamps must be positive and monotonic");
        if (detections == null) throw new IllegalArgumentException("detections required");
        for (Detection d : detections) {
            if (d.cameraId() != cameraId || d.frameUtcMs() != frameUtcMs)
                throw new IllegalArgumentException("all detections must belong to this camera/frame");
        }
        lastFrameUtcMs = frameUtcMs;

        List<Pair> pairs = new ArrayList<>();
        Map<Long, Integer> candidateCounts = new HashMap<>();
        for (State state : active) {
            for (int i = 0; i < detections.size(); i++) {
                Detection detection = detections.get(i);
                if (!state.objectClass.equals(detection.objectClass())) continue;
                double distance = centerDistance(state, detection);
                if (distance <= matchDistance) {
                    pairs.add(new Pair(state, detection, distance, i));
                    candidateCounts.merge(state.id, 1, Integer::sum);
                }
            }
        }
        // Give the most constrained tracks first choice. This avoids a flexible track
        // consuming the only candidate available to another track in crowded scenes.
        pairs.sort(Comparator.comparingInt((Pair p) -> candidateCounts.getOrDefault(p.track().id, 0))
                .thenComparingDouble(Pair::distance)
                .thenComparingLong(p -> p.track().id)
                .thenComparingInt(Pair::detectionIndex));

        Set<Long> matchedTracks = new HashSet<>();
        boolean[] matchedDetections = new boolean[detections.size()];
        for (Pair pair : pairs) {
            int index = pair.detectionIndex();
            if (!matchedTracks.contains(pair.track().id) && !matchedDetections[index]) {
                matchedTracks.add(pair.track().id);
                matchedDetections[index] = true;
                pair.track().update(pair.detection());
            }
        }

        for (State state : active) {
            if (!matchedTracks.contains(state.id)) state.missed++;
        }
        for (int i = 0; i < detections.size(); i++) {
            if (!matchedDetections[i]) active.add(new State(nextTrackId++, detections.get(i)));
        }

        active.removeIf(state -> state.missed > maxMissedFrames
                || frameUtcMs - state.lastSeen > maxAgeMs);
        return active.stream().map(state -> state.snapshot(cameraId, confirmHits)).toList();
    }

    public synchronized void reset() {
        active.clear();
        lastFrameUtcMs = 0L;
        nextTrackId = 1L;
    }

    private static double centerDistance(State a, Detection b) {
        double dx = a.centerX() - (b.x1() + b.x2()) / 2d;
        double dy = a.centerY() - (b.y1() + b.y2()) / 2d;
        return Math.hypot(dx, dy);
    }
}
