package orbit.protocol;

import java.awt.Color;
import java.awt.image.BufferedImage;

/**
 * Ordered (Bayer) dithering: a fixed pattern of thresholds that spreads rounding over neighbouring pixels, so smooth
 * colour changes don't turn into visible bands when few shades are available. Shared by the agent's idle screen and
 * the host's launch splash.
 */
public final class Dither {

    private static final int[][] BAYER = {
            {0, 32, 8, 40, 2, 34, 10, 42},
            {48, 16, 56, 24, 50, 18, 58, 26},
            {12, 44, 4, 36, 14, 46, 6, 38},
            {60, 28, 52, 20, 62, 30, 54, 22},
            {3, 35, 11, 43, 1, 33, 9, 41},
            {51, 19, 59, 27, 49, 17, 57, 25},
            {15, 47, 7, 39, 13, 45, 5, 37},
            {63, 31, 55, 23, 61, 29, 53, 21},
    };

    private Dither() {
    }

    /** The pattern's threshold at a pixel, evenly spread in [0, 1). */
    public static double threshold(int x, int y) {
        return (BAYER[y & 7][x & 7] + 0.5) / 64;
    }

    /**
     * Reduces an 8-bit channel value to {@code bits} bits, rounding up or down by the pattern so that on average
     * the original value is kept.
     */
    public static int reduce(int value, int bits, int x, int y) {
        int levels = (1 << bits) - 1;
        double scaled = value * levels / 255.0;
        int v = (int) Math.floor(scaled + threshold(x, y));
        return Math.min(levels, v);
    }

    /** A vertical gradient from {@code top} to {@code bottom}, dithered to 8 bits per channel. */
    public static BufferedImage verticalGradient(int width, int height, Color top, Color bottom) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            double t = height == 1 ? 0 : (double) y / (height - 1);
            double r = top.getRed() + (bottom.getRed() - top.getRed()) * t;
            double g = top.getGreen() + (bottom.getGreen() - top.getGreen()) * t;
            double b = top.getBlue() + (bottom.getBlue() - top.getBlue()) * t;
            for (int x = 0; x < width; x++) {
                double d = threshold(x, y);
                row[x] = (channel(r, d) << 16) | (channel(g, d) << 8) | channel(b, d);
            }
            img.setRGB(0, y, width, 1, row, 0, width);
        }
        return img;
    }

    private static int channel(double value, double threshold) {
        return Math.min(255, (int) Math.floor(value + threshold));
    }
}
