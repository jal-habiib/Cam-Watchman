package com.cctv.contract;

/**
 * Engine → app: a confirmed track crossed one counting line (plan 7b).
 *
 * <p>Contract rules (agreed 2026-11-17, CHATGPT_TO_DEEPSEEK_PHASE7_DECISIONS.md §2):
 * <ul>
 *   <li>only confirmed tracks produce events;</li>
 *   <li>at most one event per track per line, re-armed only after the track moved back to the other
 *       side, so jitter cannot double-count;</li>
 *   <li>{@code direction} is decided by the signed cross-product side change;</li>
 *   <li>track ids stay camera-local and are never merged across cameras.</li>
 * </ul>
 *
 * <p>The app persists each event in {@code count_events} and folds it into the daily
 * {@code count_summaries} row.
 */
public record LineCrossingEvent(long cameraId, long lineId, long trackId, Direction direction,
                                long crossedUtcMs) {

    public enum Direction {
        IN, OUT
    }

    public LineCrossingEvent {
        if (cameraId <= 0) {
            throw new IllegalArgumentException("cameraId must be positive");
        }
        if (lineId <= 0) {
            throw new IllegalArgumentException("lineId must be positive");
        }
        if (trackId < 0) {
            throw new IllegalArgumentException("trackId must not be negative");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction is required");
        }
        if (crossedUtcMs <= 0) {
            throw new IllegalArgumentException("crossedUtcMs must be a UTC epoch millisecond");
        }
    }
}
