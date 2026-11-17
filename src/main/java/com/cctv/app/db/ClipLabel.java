package com.cctv.app.db;

/**
 * One per-class summary of a finished clip (plan 4a). The app derives these from the engine's
 * {@link com.cctv.contract.Detection} events while the segment is open; the repository turns them
 * into {@code clip_labels} rows, keyed by the clip's {@code (cameraId, startUtcMs)}.
 */
public record ClipLabel(String objectClass, long firstSeenUtcMs, long lastSeenUtcMs,
                        double maxConfidence, int count) {

    public ClipLabel {
        if (objectClass == null || objectClass.isBlank()) {
            throw new IllegalArgumentException("objectClass is required");
        }
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1");
        }
        if (lastSeenUtcMs < firstSeenUtcMs) {
            throw new IllegalArgumentException("lastSeenUtcMs is before firstSeenUtcMs");
        }
        if (!(maxConfidence >= 0 && maxConfidence <= 1)) {
            throw new IllegalArgumentException("maxConfidence must be within 0..1");
        }
    }
}
