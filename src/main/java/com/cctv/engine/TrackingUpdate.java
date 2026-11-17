package com.cctv.engine;

import java.util.List;

/** Immutable per-frame tracking snapshot; published on the AI worker thread. */
public record TrackingUpdate(long cameraId, long frameUtcMs, List<TrackedObject> tracks) {
    public TrackingUpdate {
        if (cameraId <= 0 || frameUtcMs <= 0) throw new IllegalArgumentException("camera and timestamp required");
        tracks = List.copyOf(tracks);
        for (TrackedObject track : tracks) {
            if (track.cameraId() != cameraId) throw new IllegalArgumentException("cross-camera track");
        }
    }
}
