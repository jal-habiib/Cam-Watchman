package com.cctv.app.config;

import java.nio.file.Path;
import java.util.List;

/**
 * Validated application configuration (plan 1b).
 *
 * <p>Immutable value produced by {@link ConfigLoader}; nothing here is read from disk lazily.
 * All paths under {@link #baseDataDir()} are the only place the app writes recordings, the
 * database, logs and sidecars.
 *
 * @param configFile            file the values came from (for log messages only)
 * @param baseDataDir           root of all runtime data (recordings, DB, logs, snapshots)
 * @param cameras               validated cameras, ordered by id, never empty
 * @param segmentSeconds        MKV segment length, plan range 10–30 s
 * @param retentionDays         delete kept clips older than this
 * @param retentionMaxBytes     total recordings cap in bytes
 * @param minFreeBytes          free-space guard: stop keeping new clips below this
 * @param aiRateFps             inferences per second on Camera 1 (plan default 1)
 */
public record AppConfig(Path configFile,
                        Path baseDataDir,
                        List<CameraConfig> cameras,
                        int segmentSeconds,
                        int retentionDays,
                        long retentionMaxBytes,
                        long minFreeBytes,
                        double aiRateFps) {

    public AppConfig {
        cameras = List.copyOf(cameras);
        if (cameras.isEmpty()) {
            throw new IllegalArgumentException("configuration has no cameras");
        }
        if (segmentSeconds < ConfigLoader.MIN_SEGMENT_SECONDS || segmentSeconds > ConfigLoader.MAX_SEGMENT_SECONDS) {
            throw new IllegalArgumentException("recording.segment.seconds out of range: " + segmentSeconds);
        }
        if (retentionDays <= 0) {
            throw new IllegalArgumentException("retention.days must be positive: " + retentionDays);
        }
        if (retentionMaxBytes <= 0) {
            throw new IllegalArgumentException("retention.size.gb must be positive");
        }
        if (minFreeBytes < 0) {
            throw new IllegalArgumentException("retention.min.free.gb must not be negative");
        }
        if (aiRateFps <= 0 || aiRateFps > 60) {
            throw new IllegalArgumentException("ai.rate.fps out of range: " + aiRateFps);
        }
    }

    /** Directory for recorded MKV segments: {@code <base>/recordings}. */
    public Path recordingsDir() {
        return baseDataDir.resolve("recordings");
    }

    /** Directory for alert snapshots: {@code <base>/snapshots}. */
    public Path snapshotsDir() {
        return baseDataDir.resolve("snapshots");
    }

    /** SQLite database file: {@code <base>/cam-watchman.db}. */
    public Path databaseFile() {
        return baseDataDir.resolve("cam-watchman.db");
    }

    /** Directory for rotating log files: {@code <base>/logs}. */
    public Path logsDir() {
        return baseDataDir.resolve("logs");
    }

    /** The camera with the full pipeline (the plan assumes exactly one; the first one wins). */
    public CameraConfig fullPipelineCamera() {
        return cameras.stream()
                .filter(CameraConfig::needsMainStream)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no camera has AI or recording enabled"));
    }
}
