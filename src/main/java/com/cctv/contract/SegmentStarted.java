package com.cctv.contract;

import java.nio.file.Path;

/**
 * A recording segment file was opened and is being written (contract v0.2, decision 4).
 *
 * <p>The app inserts (or upserts) its {@code recordings} row as {@code status='writing'} keyed by
 * {@code (cameraId, startUtcMs)}. No database id exists yet.
 *
 * @param cameraId  camera row id
 * @param startUtcMs segment start, UTC epoch millis
 * @param file      absolute path of the file being written
 */
public record SegmentStarted(long cameraId, long startUtcMs, Path file) {

    public SegmentStarted {
        if (cameraId <= 0) {
            throw new IllegalArgumentException("cameraId must be positive: " + cameraId);
        }
        if (startUtcMs <= 0) {
            throw new IllegalArgumentException("startUtcMs required: " + startUtcMs);
        }
        if (file == null) {
            throw new IllegalArgumentException("file required");
        }
        file = file.toAbsolutePath().normalize();
    }
}
