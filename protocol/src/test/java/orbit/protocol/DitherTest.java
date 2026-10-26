package orbit.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DitherTest {

    @Test
    void pureColoursStayExact() {
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                assertEquals(0, Dither.reduce(0, 5, x, y));
                assertEquals(31, Dither.reduce(255, 5, x, y));
                assertEquals(63, Dither.reduce(255, 6, x, y));
            }
        }
    }

    @Test
    void reducingKeepsTheAverageShade() {
        for (int value : new int[] {14, 28, 100, 200}) {
            double sum = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    sum += Dither.reduce(value, 5, x, y);
                }
            }
            assertEquals(value * 31 / 255.0, sum / 64, 0.05, "average of " + value);
        }
    }

    @Test
    void gradientBlendsInsteadOfBanding() {
        BufferedImage img = Dither.verticalGradient(64, 1080, new Color(0x0E1726), new Color(0x1C2E45));
        assertEquals(0x0E1726, img.getRGB(0, 0) & 0xFFFFFF, "top colour exact");
        assertEquals(0x1C2E45, img.getRGB(0, 1079) & 0xFFFFFF, "bottom colour exact");
        // Between two shades, a row mixes both, so the eye sees the in-between value rather than a step.
        int y = 540;
        Set<Integer> blues = new HashSet<>();
        double sum = 0;
        for (int x = 0; x < 64; x++) {
            int b = img.getRGB(x, y) & 0xFF;
            blues.add(b);
            sum += b;
        }
        double expected = 0x26 + (0x45 - 0x26) * (y / 1079.0);
        assertEquals(expected, sum / 64, 0.5);
        assertTrue(blues.size() == 2 || Math.abs(expected - Math.round(expected)) < 0.02, "mixed shades: " + blues);
    }
}
