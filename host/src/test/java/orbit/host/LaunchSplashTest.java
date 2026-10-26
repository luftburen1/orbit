package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class LaunchSplashTest {

    @Test
    void coverCaptionAndSpinnerFitTheScreen() {
        BufferedImage cover = new BufferedImage(600, 800, BufferedImage.TYPE_INT_RGB);
        var g = cover.createGraphics();
        g.setColor(new Color(0xFB6618));
        g.fillRect(0, 0, 600, 800);
        g.dispose();
        for (int[] size : new int[][] {{1920, 1080}, {1280, 720}, {3840, 2160}}) {
            BufferedImage img = LaunchSplash.render(size[0], size[1], "Zwift", cover, 0.25);
            assertEquals(size[0], img.getWidth());
            Rectangle spinner = LaunchSplash.spinnerArea(size[0], size[1]);
            assertTrue(spinner.y + spinner.height < size[1] * 0.9, "spinner above the bottom margin");
            assertEquals(0xFB6618, img.getRGB(size[0] / 2, size[1] * 35 / 100) & 0xFFFFFF, "the cover in the middle");
        }
    }

    @Test
    void withoutACoverTheOrbitLogoShows() {
        BufferedImage img = LaunchSplash.render(1920, 1080, "Kodi", null, 0);
        // The logo's planet sits in the middle of the cover's area.
        assertNotEquals(img.getRGB(100, 1080 * 35 / 100), img.getRGB(960, 1080 * 35 / 100));
    }
}
