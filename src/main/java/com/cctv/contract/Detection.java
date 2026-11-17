package com.cctv.contract;

/**
 * One detection event, produced by the AI plane (B) on the {@link EventBus}.
 *
 * <p>Box is normalized 0..1 against the frame it came from, so it survives any display or
 * zone-coordinate scaling (plan p.3: zones are stored normalized too).
 *
 * @param cameraId    camera row id
 * @param objectClass one of: person, car, truck, motorcycle
 * @param confidence  0..1
 * @param x1          left, normalized
 * @param y1          top, normalized
 * @param x2          right, normalized
 * @param y2          bottom, normalized
 * @param frameUtcMs  wall-clock UTC capture time in epoch millis (contract v0.2 decision 8; any
 *                    container PTS stays inside the engine and never crosses this boundary)
 */
public record Detection(long cameraId, String objectClass, float confidence,
                        double x1, double y1, double x2, double y2, long frameUtcMs) {

    public Detection {
        if (objectClass == null || objectClass.isBlank()) {
            throw new IllegalArgumentException("objectClass required");
        }
        if (confidence < 0f || confidence > 1f) {
            throw new IllegalArgumentException("confidence out of range: " + confidence);
        }
        if (x1 < 0d || y1 < 0d || x2 > 1d || y2 > 1d || x2 < x1 || y2 < y1) {
            throw new IllegalArgumentException(
                    "box must be normalized 0..1 and ordered: " + x1 + "," + y1 + "," + x2 + "," + y2);
        }
    }

    /** Bottom-center of the box: the feet point used for zone tests (plan 5b). */
    public double feetX() {
        return (x1 + x2) / 2d;
    }

    public double feetY() {
        return y2;
    }

    public double width() {
        return x2 - x1;
    }

    public double height() {
        return y2 - y1;
    }
}
