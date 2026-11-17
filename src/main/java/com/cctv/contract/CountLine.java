package com.cctv.contract;

/**
 * A counting line for one camera (plan 7b): a normalized segment, not a rectangle.
 *
 * <p>Owned and persisted by the app; the engine only reads the active lines through
 * {@link LineSource} and decides when a confirmed track crosses one. Endpoints are normalized to
 * the frame (0..1), so the line survives a resolution change.
 */
public record CountLine(long id, long cameraId, String name,
                        double x1, double y1, double x2, double y2, boolean active) {

    public CountLine {
        if (id < 0) {
            throw new IllegalArgumentException("line id must not be negative");
        }
        if (cameraId <= 0) {
            throw new IllegalArgumentException("cameraId must be positive");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("line name is required");
        }
        requireUnit("x1", x1);
        requireUnit("y1", y1);
        requireUnit("x2", x2);
        requireUnit("y2", y2);
        if (Math.hypot(x2 - x1, y2 - y1) < 0.02) {
            throw new IllegalArgumentException("line is too short to be usable");
        }
    }

    private static void requireUnit(String name, double value) {
        if (!(value >= 0.0) || value > 1.000_001) {
            throw new IllegalArgumentException(name + " must be in 0..1, got " + value);
        }
    }

    /** Signed side of the point relative to the directed segment: >0 left, <0 right, 0 on it. */
    public double sideOf(double px, double py) {
        return (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1);
    }
}
