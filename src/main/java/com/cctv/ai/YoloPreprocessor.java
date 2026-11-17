package com.cctv.ai;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * YOLO11 letterbox preprocessing: source image -> square (or model-sized) RGB, NCHW,
 * 0..1 normalized float tensor, with the geometry needed to map boxes back.
 *
 * <p>Matches the Ultralytics export contract: aspect ratio preserved, the shorter side is
 * padded with gray (114,114,114) to the model input size and the image is centred. Pixel
 * values are scaled to 0..1 (no mean/std subtraction). Channel order is RGB, planar (NCHW).
 *
 * <p>This class is pure and model-free, so the geometry is unit-testable. The actual
 * input size comes from the ONNX model ({@link YoloDetector}), never hardcoded.
 */
public final class YoloPreprocessor {

    /** Ultralytics pad colour for letterbox. */
    public static final int PAD_VALUE = 114;

    /** Pixel-space mapping between the letterboxed tensor and the original frame. */
    public record Letterbox(float scale, float padX, float padY, int dstWidth, int dstHeight) {

        public Letterbox {
            if (!(scale > 0f) || Float.isInfinite(scale) || Float.isNaN(scale)) {
                throw new IllegalArgumentException("bad scale: " + scale);
            }
            if (dstWidth <= 0 || dstHeight <= 0) {
                throw new IllegalArgumentException("bad dst size: " + dstWidth + "x" + dstHeight);
            }
            if (padX < 0f || padY < 0f) {
                throw new IllegalArgumentException("negative pad: " + padX + "," + padY);
            }
        }

        /** Scaled content width inside the tensor (dstWidth - 2*padX). */
        public float contentWidth() {
            return dstWidth - 2f * padX;
        }

        /** Scaled content height inside the tensor (dstHeight - 2*padY). */
        public float contentHeight() {
            return dstHeight - 2f * padY;
        }

        /** Original-frame x -> tensor x. */
        public float toTensorX(float srcX) {
            return srcX * scale + padX;
        }

        /** Original-frame y -> tensor y. */
        public float toTensorY(float srcY) {
            return srcY * scale + padY;
        }

        /** Tensor x -> original-frame x (may fall in the pad; caller decides). */
        public float fromTensorX(float dstX) {
            return (dstX - padX) / scale;
        }

        /** Tensor y -> original-frame y (may fall in the pad; caller decides). */
        public float fromTensorY(float dstY) {
            return (dstY - padY) / scale;
        }

        /** True when (x,y) in tensor space lies inside the scaled image, not the pad. */
        public boolean insideContent(float dstX, float dstY) {
            return dstX >= padX && dstX <= padX + contentWidth()
                    && dstY >= padY && dstY <= padY + contentHeight();
        }

        public int contentLeft() {
            return Math.round(padX);
        }

        public int contentTop() {
            return Math.round(padY);
        }

        public int contentRight() {
            return Math.round(padX + contentWidth());
        }

        public int contentBottom() {
            return Math.round(padY + contentHeight());
        }
    }

    /** Preprocessed tensor plus the geometry it was built with. */
    public record Preprocessed(float[] nchw, Letterbox letterbox) {

        public Preprocessed {
            if (nchw == null) {
                throw new IllegalArgumentException("tensor required");
            }
            int expected = 3 * letterbox.dstWidth() * letterbox.dstHeight();
            if (nchw.length != expected) {
                throw new IllegalArgumentException(
                        "tensor length " + nchw.length + " != 3*" + letterbox.dstWidth()
                                + "*" + letterbox.dstHeight() + " = " + expected);
            }
        }

        public int inputWidth() {
            return letterbox.dstWidth();
        }

        public int inputHeight() {
            return letterbox.dstHeight();
        }
    }

    private YoloPreprocessor() {
    }

    /**
     * Letterbox geometry for {@code srcW x srcH} into {@code dstW x dstH}: uniform scale to
     * fit, then centred padding. Same aspect ratio inside a square model input gives zero pad.
     */
    public static Letterbox letterbox(int srcW, int srcH, int dstW, int dstH) {
        if (srcW <= 0 || srcH <= 0) {
            throw new IllegalArgumentException("bad source size: " + srcW + "x" + srcH);
        }
        if (dstW <= 0 || dstH <= 0) {
            throw new IllegalArgumentException("bad model size: " + dstW + "x" + dstH);
        }
        float scale = Math.min((float) dstW / srcW, (float) dstH / srcH);
        float newW = srcW * scale;
        float newH = srcH * scale;
        float padX = (dstW - newW) / 2f;
        float padY = (dstH - newH) / 2f;
        return new Letterbox(scale, padX, padY, dstW, dstH);
    }

    /** Convenience: letterbox + tensor in one call for a square model input. */
    public static Preprocessed preprocess(BufferedImage src, int dstW, int dstH) {
        Letterbox lb = letterbox(src.getWidth(), src.getHeight(), dstW, dstH);
        return new Preprocessed(toNchw(src, lb), lb);
    }

    /**
     * Builds the CHW float tensor for the given letterbox geometry.
     *
     * <p>Rendering goes through Java2D bilinear scaling into a gray canvas, so the result is
     * the same "resize then pad" the Ultralytics Python pipeline does (cv2.resize +
     * copyMakeBorder), without pulling in OpenCV/JavaCV for the still-image path.
     */
    public static float[] toNchw(BufferedImage src, Letterbox lb) {
        BufferedImage canvas = renderLetterbox(src, lb);
        int w = lb.dstWidth();
        int h = lb.dstHeight();
        int plane = w * h;
        float[] tensor = new float[3 * plane];
        int[] argb = canvas.getRGB(0, 0, w, h, null, 0, w);
        final float inv = 1f / 255f;
        for (int i = 0; i < plane; i++) {
            int p = argb[i];
            tensor[i] = ((p >> 16) & 0xFF) * inv;            // R plane
            tensor[plane + i] = ((p >> 8) & 0xFF) * inv;     // G plane
            tensor[2 * plane + i] = (p & 0xFF) * inv;        // B plane
        }
        return tensor;
    }

    /** Draws {@code src} letterboxed into a fresh RGB canvas filled with the pad colour. */
    public static BufferedImage renderLetterbox(BufferedImage src, Letterbox lb) {
        BufferedImage canvas = new BufferedImage(
                lb.dstWidth(), lb.dstHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(new java.awt.Color(PAD_VALUE, PAD_VALUE, PAD_VALUE));
            g.fillRect(0, 0, lb.dstWidth(), lb.dstHeight());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, lb.contentLeft(), lb.contentTop(),
                    lb.contentRight() - lb.contentLeft(),
                    lb.contentBottom() - lb.contentTop(), null);
        } finally {
            g.dispose();
        }
        return canvas;
    }
}
