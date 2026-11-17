package com.cctv.contract;

import java.nio.file.Path;
import java.util.UUID;

/**
 * A persisted zone alert (plan 5d): the app has written the snapshot file and the {@code alerts}
 * row, and the UI can present it. Published on the {@link EventBus} inside the app.
 *
 * @param alertId             UUID string, allocated by the app
 * @param cameraId            camera row id
 * @param zoneId              zone that fired
 * @param recordingStartUtcMs start of the recording the alert belongs to, or 0 when not recording
 * @param snapshotFile        absolute path of the PNG written next to the recordings
 * @param createdUtcMs        wall-clock UTC when the alert row was created
 */
public record ZoneAlert(String alertId, long cameraId, long zoneId, long recordingStartUtcMs,
                        Path snapshotFile, long createdUtcMs) {

    public ZoneAlert {
        if (alertId == null || alertId.isBlank()) {
            throw new IllegalArgumentException("alertId required");
        }
        UUID.fromString(alertId);
        if (cameraId <= 0) {
            throw new IllegalArgumentException("cameraId must be positive: " + cameraId);
        }
        if (zoneId <= 0) {
            throw new IllegalArgumentException("zoneId must be positive: " + zoneId);
        }
        if (recordingStartUtcMs < 0) {
            throw new IllegalArgumentException("recordingStartUtcMs must be >= 0: " + recordingStartUtcMs);
        }
        if (snapshotFile == null) {
            throw new IllegalArgumentException("snapshotFile required");
        }
        snapshotFile = snapshotFile.toAbsolutePath().normalize();
        if (createdUtcMs <= 0) {
            throw new IllegalArgumentException("createdUtcMs required: " + createdUtcMs);
        }
    }

    public static ZoneAlert create(long cameraId, long zoneId, long recordingStartUtcMs,
                                   Path snapshotFile, long createdUtcMs) {
        return new ZoneAlert(UUID.randomUUID().toString(), cameraId, zoneId, recordingStartUtcMs,
                snapshotFile, createdUtcMs);
    }
}
