package orbit.tray;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import orbit.protocol.Logo;
import org.junit.jupiter.api.Test;

class TrayIconsTest {

    @Test
    void trayIconIsThePlainLogoWhileIdle() {
        assertArrayEquals(pixels(Logo.image(22)), pixels(TrayIcons.trayIcon(22, false)));
    }

    @Test
    void planetBecomesAPlayButtonWhileStreaming() {
        BufferedImage icon = TrayIcons.trayIcon(32, true);
        // The planet's left side, inside the circle but outside the white triangle.
        assertEquals(0xFF2E9E5B, icon.getRGB(11, 16));
        assertEquals(0xFFFFFFFF, icon.getRGB(16, 16), "white play triangle in the middle");
    }

    @Test
    void smallTrayIconHasAPlainGreenPlanetWhileStreaming() {
        assertEquals(0xFF2E9E5B, TrayIcons.trayIcon(16, true).getRGB(8, 8));
    }

    private static int[] pixels(BufferedImage img) {
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }
}
