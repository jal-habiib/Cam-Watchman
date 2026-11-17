package com.cctv.contract;

import java.awt.image.BufferedImage;
import java.util.Optional;

/**
 * Implemented by the capture plane (A), used by the zone-drawing UI (plan 5c) to freeze a frame.
 */
public interface SnapshotProvider {

    /** Latest frame of the camera, or empty when the stream is offline. */
    Optional<BufferedImage> snapshot(long cameraId);
}
