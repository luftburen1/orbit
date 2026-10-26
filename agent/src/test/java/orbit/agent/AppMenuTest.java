package orbit.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class AppMenuTest {

    private static final List<String> APPS = List.of("Desktop", "Steam Big Picture", "Kodi");

    @Test
    void opensOnTheLastAppOrTheFirst() {
        assertEquals("Steam Big Picture", AppMenu.of(APPS, "Steam Big Picture").app());
        assertEquals("Desktop", AppMenu.of(APPS, "Removed since").app());
        assertEquals("Desktop", AppMenu.of(APPS, null).app());
        assertThrows(IllegalArgumentException.class, () -> AppMenu.of(List.of(), null));
    }

    @Test
    void upAndDownWrapAround() {
        AppMenu menu = AppMenu.of(APPS, "Desktop");
        assertEquals("Kodi", menu.up().app());
        assertEquals("Steam Big Picture", menu.down().app());
        assertEquals("Desktop", menu.down().down().down().app());
    }

    @Test
    void scrollsToKeepTheSelectedAppInView() {
        List<String> many = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i");
        assertEquals(0, new AppMenu(many, 0).firstVisible(5));
        assertEquals(0, new AppMenu(many, 2).firstVisible(5));
        assertEquals(2, new AppMenu(many, 4).firstVisible(5), "selected in the middle");
        assertEquals(4, new AppMenu(many, 8).firstVisible(5), "no empty rows at the end");
        assertEquals(0, new AppMenu(APPS, 2).firstVisible(5), "all fit");
    }

    @Test
    void iconsFromTheHostGoWithTheirApps() throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 4, BufferedImage.TYPE_INT_RGB), "png", png);
        String icon = Base64.getEncoder().encodeToString(png.toByteArray());

        AppMenu menu = AppMenu.of(APPS, Arrays.asList(null, icon, "not base64!"), "Desktop");
        assertTrue(menu.hasIcons());
        assertNull(menu.icon(0));
        assertNotNull(menu.icon(1));
        assertNull(menu.icon(2), "a broken icon is left out");
        assertSame(menu.icon(1), menu.down().icon(1), "decoded once, not on every move");

        assertFalse(AppMenu.of(APPS, null, null).hasIcons(), "older hosts send none");
        assertFalse(AppMenu.of(APPS, List.of(icon), null).hasIcons(), "not one per app: they'd go on wrong rows");
    }
}
