package com.cctv.engine;

import com.cctv.contract.CountLine;
import com.cctv.contract.LineCrossingEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects crossings of normalized counting-line segments using confirmed camera-local tracks.
 *
 * <p>The tracked point is the bottom-centre (feet) of each bounding box. A small perpendicular
 * distance deadband prevents detector jitter around a line from generating duplicate events.
 * IN means movement from the negative to positive signed side of the directed line; OUT is the
 * reverse. Create one detector per camera and call once per inference frame.
 */
public final class LineCrossingDetector {
    public static final double DEFAULT_HYSTERESIS = 0.0125;

    private record Key(long lineId, long trackId) { }
    private record State(CountLine line, int stableSide) { }

    private final long cameraId;
    private final double hysteresis;
    private final Map<Key, State> states = new HashMap<>();
    private long lastFrameUtcMs;

    public LineCrossingDetector(long cameraId) {
        this(cameraId, DEFAULT_HYSTERESIS);
    }

    public LineCrossingDetector(long cameraId, double hysteresis) {
        if (cameraId <= 0) throw new IllegalArgumentException("cameraId must be positive");
        if (!Double.isFinite(hysteresis) || hysteresis < 0 || hysteresis > 0.25)
            throw new IllegalArgumentException("hysteresis must be in [0, 0.25]");
        this.cameraId = cameraId;
        this.hysteresis = hysteresis;
    }

    /** Returns zero or more crossing events for this camera/frame. */
    public synchronized List<LineCrossingEvent> update(long frameUtcMs,
            List<CountLine> lines, List<TrackedObject> tracks) {
        if (frameUtcMs <= 0 || frameUtcMs < lastFrameUtcMs)
            throw new IllegalArgumentException("frame timestamps must be positive and monotonic");
        if (lines == null || tracks == null)
            throw new IllegalArgumentException("lines and tracks are required");
        lastFrameUtcMs = frameUtcMs;

        Map<Long, CountLine> activeLines = new HashMap<>();
        for (CountLine line : lines) {
            if (line.cameraId() != cameraId)
                throw new IllegalArgumentException("counting line belongs to another camera");
            if (line.active()) activeLines.put(line.id(), line);
        }

        Set<Key> observed = new HashSet<>();
        List<LineCrossingEvent> events = new ArrayList<>();
        for (TrackedObject track : tracks) {
            if (track.cameraId() != cameraId)
                throw new IllegalArgumentException("track belongs to another camera");
            if (!track.confirmed() || track.missedFrames() > 0) continue;
            double px = track.feetX();
            double py = track.feetY();
            for (CountLine line : activeLines.values()) {
                Key key = new Key(line.id(), track.trackId());
                observed.add(key);
                double signedDistance = line.sideOf(px, py)
                        / Math.hypot(line.x2() - line.x1(), line.y2() - line.y1());
                int side = signedDistance > hysteresis ? 1
                        : signedDistance < -hysteresis ? -1 : 0;
                State previous = states.get(key);
                if (previous == null || !previous.line().equals(line)) {
                    if (side != 0) states.put(key, new State(line, side));
                    else states.remove(key);
                    continue;
                }
                if (side != 0 && side != previous.stableSide()) {
                    LineCrossingEvent.Direction direction = side > 0
                            ? LineCrossingEvent.Direction.IN : LineCrossingEvent.Direction.OUT;
                    events.add(new LineCrossingEvent(cameraId, line.id(), track.trackId(),
                            direction, frameUtcMs));
                    states.put(key, new State(line, side));
                }
            }
        }
        // Forget expired tracks, deleted/inactive lines, and tracks no longer supplied by tracker.
        states.keySet().removeIf(key -> !observed.contains(key));
        return List.copyOf(events);
    }

    public synchronized void reset() {
        states.clear();
        lastFrameUtcMs = 0L;
    }
}