package com.cctv.contract;

import java.awt.image.BufferedImage;

/**
 * One decoded, downscaled frame handed from the capture plane (A) to the consumers
 * (display and AI). Coordinates in {@link Detection} are normalized against this frame.
 *
 * @param cameraId camera row id
 * @param ptsMs presentation timestamp from the grabber, milliseconds
 * @param capturedUtcMs wall-clock UTC time when the frame was decoded, epoch milliseconds
 * @param width decoded width after downscale
 * @param height decoded height after downscale
 * @param image RGB image, sized {@code width} x {@code height}
 */
public record Frame(long cameraId, long ptsMs, long capturedUtcMs,
                    int width, int height, BufferedImage image) {

    public Frame {
        if (cameraId <= 0) {
            throw new IllegalArgumentException("cameraId must be positive");
        }
        if (capturedUtcMs <= 0) {
            throw new IllegalArgumentException("capturedUtcMs must be positive");
        }
        if (image == null) {
            throw new IllegalArgumentException("image required");
        }
        if (image.getWidth() != width || image.getHeight() != height) {
            throw new IllegalArgumentException(
                    "image is " + image.getWidth() + "x" + image.getHeight()
                            + " but frame says " + width + "x" + height);
        }
    }

    /** Backwards-compatible constructor for callers that do not yet supply capture time. */
    public Frame(long cameraId, long ptsMs, int width, int height, BufferedImage image) {
        this(cameraId, ptsMs, System.currentTimeMillis(), width, height, image);
    }
}
