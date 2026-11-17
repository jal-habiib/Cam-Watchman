package com.cctv.contract;

import java.nio.file.Path;

/**
 * A recording segment file was closed and is final (contract v0.2, decision 4). Exactly once per
 * segment.
 *
 * <p>The app upserts the {@code recordings} row as {@code status='ready'}, then writes the
 * {@code clip_labels} summary when labels exist. Segments with {@code keep=false} are still
 * finalized here: the app keeps the row until retention removes it after its grace period, so the
 * database and the filesystem stay in step.
 *
 * @param cameraId  camera row id
 * @param startUtcMs segment start, UTC epoch millis (half of the segment key)
 * @param endUtcMs  segment end, UTC epoch millis
 * @param sizeBytes file size at close
 * @param file      absolute path of the finalized file
 * @param keep      true when a detection overlapped this segment (plan 3b)
 */
public record ClipFinalized(long cameraId, long startUtcMs, long endUtcMs, long sizeBytes,
                            Path file, boolean keep) {

    public ClipFinalized {
        if (cameraId <= 0) {
            throw new IllegalArgumentException("cameraId must be positive: " + cameraId);
        }
        if (startUtcMs <= 0) {
            throw new IllegalArgumentException("startUtcMs required: " + startUtcMs);
        }
        if (endUtcMs < startUtcMs) {
            throw new IllegalArgumentException("endUtcMs before startUtcMs: " + endUtcMs);
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be >= 0: " + sizeBytes);
        }
        if (file == null) {
            throw new IllegalArgumentException("file required");
        }
        file = file.toAbsolutePath().normalize();
    }
}
