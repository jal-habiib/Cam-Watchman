package com.cctv.contract;

/**
 * One stream changed connection state (plan 1c: the Online/Offline badges).
 *
 * @param cameraId     camera row id
 * @param state        connectivity only, never recording
 * @param occurredUtcMs when the state became true, UTC epoch millis
 * @param detail       short human text for the cell, never a credential (already redacted)
 */
public record StreamStatusEvent(long cameraId, StreamState state, long occurredUtcMs, String detail) {

    public StreamStatusEvent {
        if (cameraId <= 0) {
            throw new IllegalArgumentException("cameraId must be positive: " + cameraId);
        }
        if (state == null) {
            throw new IllegalArgumentException("state required");
        }
        if (occurredUtcMs <= 0) {
            throw new IllegalArgumentException("occurredUtcMs required: " + occurredUtcMs);
        }
        detail = detail == null ? "" : detail;
    }

    /** Convenience for publishers: stamps the event with the current wall clock. */
    public static StreamStatusEvent now(long cameraId, StreamState state, String detail) {
        return new StreamStatusEvent(cameraId, state, System.currentTimeMillis(), detail);
    }
}
