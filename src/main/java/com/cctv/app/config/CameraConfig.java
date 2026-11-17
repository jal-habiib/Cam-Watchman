package com.cctv.app.config;

import java.net.URI;
import java.util.Objects;

/**
 * One camera as configured by {@code cameras.properties} (plan 1b).
 *
 * <p>Camera 1 (or any camera with {@code ai} or {@code record} enabled) must have a main-stream
 * {@link #mainUrl()}. Display-only cameras need only a {@link #substreamUrl()}. One connection
 * per stream profile is the caller's responsibility: this record only describes the profiles.
 *
 * @param id           stable numeric id, unique across the file and used in paths/DB
 * @param name         short human name shown on the wall
 * @param mainUrl      main-stream RTSP URL, or {@code null} when not configured
 * @param substreamUrl substream RTSP URL, or {@code null} when not configured
 * @param record       whether the full pipeline records this camera
 * @param ai           whether the full pipeline runs detection on it
 */
public record CameraConfig(long id, String name, URI mainUrl, URI substreamUrl, boolean record, boolean ai) {

    public CameraConfig {
        Objects.requireNonNull(name, "name");
        if (id <= 0) {
            throw new IllegalArgumentException("camera id must be positive: " + id);
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("camera " + id + " has a blank name");
        }
    }

    public boolean hasMain() {
        return mainUrl != null;
    }

    public boolean hasSubstream() {
        return substreamUrl != null;
    }

    /** True when the camera needs the single main-stream connection (AI or recording). */
    public boolean needsMainStream() {
        return record || ai;
    }

    /** The URL a display cell should use: substream if present, else main stream. */
    public URI displayUrl() {
        return hasSubstream() ? substreamUrl : mainUrl;
    }
}
