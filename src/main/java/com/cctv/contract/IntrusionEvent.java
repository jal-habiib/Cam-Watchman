package com.cctv.contract;

import java.awt.image.BufferedImage;

/**
 * A confirmed zone intrusion, published by the engine once per entry (contract v0.2, decisions
 * 5-7). The engine has already applied the 2-consecutive-detection confirmation and the 60 s
 * per-zone cooldown, so the app publishes one popup per event without re-deciding anything.
 *
 * <p>The snapshot travels with the event as a deep copy owned by the app: the engine must never
 * hand out its live frame buffer. Convert and write it off the JavaFX thread.
 *
 * @param cameraId           camera row id
 * @param zone               the zone that fired, including its name
 * @param detectedUtcMs      wall-clock UTC of the confirming detection
 * @param recordingStartUtcMs start of the segment being written, or 0 when not recording
 * @param snapshot           full-resolution copy of the confirming frame
 */
public record IntrusionEvent(long cameraId, ZoneDefinition zone, long detectedUtcMs,
                             long recordingStartUtcMs, BufferedImage snapshot) {

    public IntrusionEvent {
        if (cameraId <= 0) {
            throw new IllegalArgumentException("cameraId must be positive: " + cameraId);
        }
        if (zone == null) {
            throw new IllegalArgumentException("zone required");
        }
        if (detectedUtcMs <= 0) {
            throw new IllegalArgumentException("detectedUtcMs required: " + detectedUtcMs);
        }
        if (recordingStartUtcMs < 0) {
            throw new IllegalArgumentException("recordingStartUtcMs must be >= 0: " + recordingStartUtcMs);
        }
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot required");
        }
    }

    /** Independent copy of the snapshot so the receiver can convert or scale it freely. */
    public BufferedImage snapshotCopy() {
        int width = snapshot.getWidth();
        int height = snapshot.getHeight();
        int type = snapshot.getType() == 0 ? BufferedImage.TYPE_INT_ARGB : snapshot.getType();
        BufferedImage copy = new BufferedImage(width, height, type);
        var g = copy.createGraphics();
        try {
            g.drawImage(snapshot, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }
}
