package com.cctv.ai;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Letterbox geometry and NCHW tensor layout (spike 0c / item 2b), no model involved. */
class YoloPreprocessorTest {

    private static final float EPS = 1e-5f;

    @Test
    void sixteenByNineGoesToFullWidthWithVerticalPad() {
        // 1920x1080 -> 320: scale 1/6, height 180, 70px pad top and bottom.
        YoloPreprocessor.Letterbox lb = YoloPreprocessor.letterbox(1920, 1080, 320, 320);
        assertEquals(1f / 6f, lb.scale(), EPS);
        assertEquals(0f, lb.padX(), EPS);
        assertEquals(70f, lb.padY(), EPS);
        assertEquals(320f, lb.contentWidth(), EPS);
        assertEquals(180f, lb.contentHeight(), EPS);
    }

    @Test
    void portraitGoesToFullHeightWithHorizontalPad() {
        YoloPreprocessor.Letterbox lb = YoloPreprocessor.letterbox(1080, 1920, 320, 320);
        assertEquals(1f / 6f, lb.scale(), EPS);
        assertEquals(70f, lb.padX(), EPS);
        assertEquals(0f, lb.padY(), EPS);
        assertEquals(180f, lb.contentWidth(), EPS);
        assertEquals(320f, lb.contentHeight(), EPS);
    }

    @Test
    void fourByThreePadsOnlyVertically() {
        // 640x480 -> 320: scale 0.5, height 240, pad 40.
        YoloPreprocessor.Letterbox lb = YoloPreprocessor.letterbox(640, 480, 320, 320);
        assertEquals(0.5f, lb.scale(), EPS);
        assertEquals(0f, lb.padX(), EPS);
        assertEquals(40f, lb.padY(), EPS);
        assertEquals(320f, lb.contentWidth(), EPS);
        assertEquals(240f, lb.contentHeight(), EPS);
    }

    @Test
    void squareSourceIsNotPadded() {
        YoloPreprocessor.Letterbox lb = YoloPreprocessor.letterbox(320, 320, 320, 320);
        assertEquals(1f, lb.scale(), EPS);
        assertEquals(0f, lb.padX(), EPS);
        assertEquals(0f, lb.padY(), EPS);
    }

    @Test
    void padIsCentredToTheNearestPixel() {
        // 100x100 -> 320 (upscale by 3.2) and 320x200 -> 320 (pad 60 each side).
        YoloPreprocessor.Letterbox up = YoloPreprocessor.letterbox(100, 100, 320, 320);
        assertEquals(3.2f, up.scale(), EPS);
        assertEquals(0f, up.padX(), EPS);
        YoloPreprocessor.Letterbox wide = YoloPreprocessor.letterbox(320, 200, 320, 320);
        assertEquals(60f, wide.padY(), EPS);
        assertEquals(0f, wide.padX(), EPS);
        assertEquals(60, wide.contentTop());
        assertEquals(260, wide.contentBottom());
    }

    @Test
    void coordinateRoundTrip() {
        YoloPreprocessor.Letterbox lb = YoloPreprocessor.letterbox(1920, 1080, 320, 320);
        float x = 1234.5f;
        float y = 678.25f;
        assertEquals(x, lb.fromTensorX(lb.toTensorX(x)), 1e-2f);
        assertEquals(y, lb.fromTensorY(lb.toTensorY(y)), 1e-2f);
        assertTrue(lb.insideContent(lb.toTensorX(x), lb.toTensorY(y)));
        assertFalse(lb.insideContent(160f, 5f), "top pad is outside the scaled image");
    }

    @Test
    void rejectsBadSizes() {
        assertThrows(IllegalArgumentException.class,
                () -> YoloPreprocessor.letterbox(0, 100, 320, 320));
        assertThrows(IllegalArgumentException.class,
                () -> YoloPreprocessor.letterbox(100, 100, 0, 320));
    }

    @Test
    void tensorIsPaddedNchwRgbAndNormalized() {
        // Solid blue 2x1 image -> 4x4 tensor: scale 2, 4x2 content, 1px pad top and bottom.
        BufferedImage blue = solid(2, 1, Color.BLUE);
        YoloPreprocessor.Letterbox lb = YoloPreprocessor.letterbox(2, 1, 4, 4);
        float[] t = YoloPreprocessor.toNchw(blue, lb);

        assertEquals(3 * 4 * 4, t.length);
        int plane = 4 * 4;
        float pad = YoloPreprocessor.PAD_VALUE / 255f;

        // Row 0 is pad: all three planes carry the 114 gray value.
        for (int i = 0; i < 4; i++) {
            assertEquals(pad, t[i], EPS, "R pad row 0");
            assertEquals(pad, t[plane + i], EPS, "G pad row 0");
            assertEquals(pad, t[2 * plane + i], EPS, "B pad row 0");
        }
        // Content rows 1..2: pure blue -> R=0, G=0, B=1 (channel order R,G,B planes).
        for (int row = 1; row <= 2; row++) {
            for (int col = 0; col < 4; col++) {
                int i = row * 4 + col;
                assertEquals(0f, t[i], EPS, "R at " + i);
                assertEquals(0f, t[plane + i], EPS, "G at " + i);
                assertEquals(1f, t[2 * plane + i], EPS, "B at " + i);
            }
        }
        // Last row pad again.
        assertEquals(pad, t[3 * 4], EPS);
    }

    @Test
    void redChannelLandsInFirstPlane() {
        BufferedImage red = solid(2, 2, new Color(255, 0, 0));
        float[] t = YoloPreprocessor.toNchw(red, YoloPreprocessor.letterbox(2, 2, 2, 2));
        int plane = 4;
        for (int i = 0; i < plane; i++) {
            assertEquals(1f, t[i], EPS, "R");
            assertEquals(0f, t[plane + i], EPS, "G");
            assertEquals(0f, t[2 * plane + i], EPS, "B");
        }
    }

    @Test
    void preprocessReportsMatchingTensorAndGeometry() {
        BufferedImage img = solid(640, 480, Color.GRAY);
        YoloPreprocessor.Preprocessed pp = YoloPreprocessor.preprocess(img, 320, 320);
        assertArrayEquals(new int[] {320, 320},
                new int[] {pp.inputWidth(), pp.inputHeight()});
        assertEquals(3 * 320 * 320, pp.nchw().length);
        assertEquals(40f, pp.letterbox().padY(), EPS);
    }

    private static BufferedImage solid(int w, int h, Color color) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }
}
