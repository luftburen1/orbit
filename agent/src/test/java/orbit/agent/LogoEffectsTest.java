package orbit.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class LogoEffectsTest {

    private static final int W = 1280;
    private static final int H = 720;

    private static int[] frame(double seconds, boolean animate) {
        Rectangle region = LogoEffects.region(W, H);
        BufferedImage area = new BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = area.createGraphics();
        g.setColor(new Color(0x14, 0x22, 0x36));
        g.fillRect(0, 0, region.width, region.height);
        g.dispose();
        LogoEffects.paint(area, region, W, H, seconds, animate);
        return area.getRGB(0, 0, region.width, region.height, null, 0, region.width);
    }

    @Test
    void regionHoldsTheLogoAndStaysOnScreen() {
        Rectangle region = LogoEffects.region(W, H);
        assertTrue(region.contains(LogoEffects.logo(W, H).getBounds()));
        assertTrue(new Rectangle(0, 0, W, H).contains(region));
        assertTrue(region.width < W / 2, "small enough to redraw cheaply: " + region);
    }

    @Test
    void animationChangesOverTimeButStillLogoDoesNot() {
        assertFalse(Arrays.equals(frame(0.0, true), frame(1.0, true)), "sparkles and glow move");
        assertArrayEquals(frame(0.0, false), frame(3.0, false), "without animation every frame is the same");
    }

    @Test
    void glowFadesOutBeforeTheRegionsEdgeSoNoSquareShows() {
        Rectangle region = LogoEffects.region(W, H);
        int[] pixels = frame(2.5, true);
        int background = new Color(0x14, 0x22, 0x36).getRGB();
        for (int x = 0; x < region.width; x++) {
            assertEquals(background, pixels[x], "top edge, x=" + x);
            assertEquals(background, pixels[(region.height - 1) * region.width + x], "bottom edge, x=" + x);
        }
        for (int y = 0; y < region.height; y++) {
            assertEquals(background, pixels[y * region.width], "left edge, y=" + y);
            assertEquals(background, pixels[y * region.width + region.width - 1], "right edge, y=" + y);
        }
    }
}
