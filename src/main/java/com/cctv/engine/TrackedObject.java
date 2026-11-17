package com.cctv.engine;

/** One camera-local object identity and its latest normalized bounding box. */
public record TrackedObject(long cameraId, long trackId, String objectClass,
                            float confidence, double x1, double y1, double x2, double y2,
                            long firstSeenUtcMs, long lastSeenUtcMs, int hits,
                            int missedFrames, boolean confirmed) {
    public TrackedObject {
        if (cameraId <= 0 || trackId <= 0) throw new IllegalArgumentException("positive ids required");
        if (objectClass == null || objectClass.isBlank()) throw new IllegalArgumentException("class required");
        if (!Float.isFinite(confidence) || confidence < 0f || confidence > 1f)
            throw new IllegalArgumentException("confidence out of range");
        if (x1 < 0 || y1 < 0 || x2 > 1 || y2 > 1 || x2 < x1 || y2 < y1)
            throw new IllegalArgumentException("normalized ordered box required");
        if (firstSeenUtcMs <= 0 || lastSeenUtcMs < firstSeenUtcMs || hits < 1 || missedFrames < 0)
            throw new IllegalArgumentException("invalid track lifecycle");
    }

    public double centerX() { return (x1 + x2) / 2d; }
    public double centerY() { return (y1 + y2) / 2d; }
    public double feetX() { return centerX(); }
    public double feetY() { return y2; }
}
