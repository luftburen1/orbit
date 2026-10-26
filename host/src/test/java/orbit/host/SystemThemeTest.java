package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;
import org.junit.jupiter.api.Test;

class SystemThemeTest {

    @Test
    void readsThePortalsColorScheme() {
        assertEquals(Optional.of(true), SystemTheme.fromPortal(new Variant<>(new UInt32(1))));
        assertEquals(Optional.of(false), SystemTheme.fromPortal(new Variant<>(new UInt32(2))));
        assertEquals(Optional.empty(), SystemTheme.fromPortal(new Variant<>(new UInt32(0))), "no preference");
        assertEquals(Optional.of(true), SystemTheme.fromPortal(new Variant<>(new Variant<>(new UInt32(1)))),
                "older portals wrap the value twice");
    }

    @Test
    void readsTheWindowsRegistry() {
        String dark = """

                HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize
                    AppsUseLightTheme    REG_DWORD    0x0
                """;
        assertEquals(Optional.of(true), SystemTheme.fromWindowsRegistry(dark));
        assertEquals(Optional.of(false), SystemTheme.fromWindowsRegistry(dark.replace("0x0", "0x1")));
        assertEquals(Optional.empty(), SystemTheme.fromWindowsRegistry(
                "ERROR: The system was unable to find the specified registry key or value."));
    }

    @Test
    void choiceOverridesTheSystem() {
        assertTrue(ThemeSwitcher.dark(ThemeSwitcher.Choice.SYSTEM, true));
        assertFalse(ThemeSwitcher.dark(ThemeSwitcher.Choice.SYSTEM, false));
        assertFalse(ThemeSwitcher.dark(ThemeSwitcher.Choice.LIGHT, true));
        assertTrue(ThemeSwitcher.dark(ThemeSwitcher.Choice.DARK, false));
    }

    @Test
    void savedChoiceDefaultsToSystem() {
        assertEquals(ThemeSwitcher.Choice.DARK, ThemeSwitcher.Choice.parse("dark"));
        assertEquals(ThemeSwitcher.Choice.SYSTEM, ThemeSwitcher.Choice.parse(null));
        assertEquals(ThemeSwitcher.Choice.SYSTEM, ThemeSwitcher.Choice.parse("solarized"));
        assertEquals("light", ThemeSwitcher.Choice.LIGHT.id());
    }
}
