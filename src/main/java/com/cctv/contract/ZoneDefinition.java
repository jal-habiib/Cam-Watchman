package com.cctv.contract;

/**
 * One intrusion zone, stored and tested in normalized 0..1 coordinates against the frame the
 * engine decoded, so it survives any display size or decode resolution (plan 5a/5b).
 *
 * <p>The zone is a rectangle given by its top-left corner plus width and height. The zone test
 * itself stays in the engine (2 consecutive detections + 60 s cooldown, contract v0.2 decision 7);
 * {@link #contains(double, double)} is provided for the editor preview and unit tests.
 *
 * @param id       zone id from the zones table (engine never allocates one)
 * @param cameraId camera the zone belongs to
 * @param name     short label shown in the alert popup
 * @param x        left, 0..1
 * @param y        top, 0..1
 * @param w        width, 0..1
 * @param h        height, 0..1
 * @param active   false parks the zone without deleting it
 */
public record ZoneDefinition(long id, long cameraId, String name,
                             double x, double y, double w, double h, boolean active) {

    public ZoneDefinition {
        if (id <= 0) {
            throw new IllegalArgumentException("zone id must be positive: " + id);
        }
        if (cameraId <= 0) {
            throw new IllegalArgumentException("cameraId must be positive: " + cameraId);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("zone name required");
        }
        requireUnit("x", x);
        requireUnit("y", y);
        if (w <= 0d || h <= 0d) {
            throw new IllegalArgumentException("zone size must be positive: " + w + "x" + h);
        }
        if (x + w > 1d + 1e-9 || y + h > 1d + 1e-9) {
            throw new IllegalArgumentException(
                    "zone must stay inside the frame: x+w=" + (x + w) + ", y+h=" + (y + h));
        }
    }

    private static void requireUnit(String name, double value) {
        if (value < 0d || value > 1d) {
            throw new IllegalArgumentException(name + " must be normalized 0..1: " + value);
        }
    }

    /** True when the normalized point is inside the rectangle (inclusive edges). */
    public boolean contains(double px, double py) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    /** The plan's feet-point test: the bottom-centre of a detection box. */
    public boolean containsFeetPoint(Detection detection) {
        return contains(detection.feetX(), detection.feetY());
    }

    public ZoneDefinition withActive(boolean nowActive) {
        return new ZoneDefinition(id, cameraId, name, x, y, w, h, nowActive);
    }
}
