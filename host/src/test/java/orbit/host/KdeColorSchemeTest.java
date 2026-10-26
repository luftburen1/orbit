package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KdeColorSchemeTest {

    private static final String NORD = """
            [ColorEffects:Disabled]
            Color=56,56,56

            [Colors:Button]
            BackgroundNormal=67,76,94
            ForegroundNormal=216,222,233

            [Colors:Selection]
            BackgroundNormal=109,150,164
            ForegroundNormal=46,52,64

            [Colors:View]
            BackgroundAlternate=50,57,70
            BackgroundNormal=46,52,64
            DecorationFocus=136,192,208
            ForegroundNormal=216,222,233

            [Colors:Window]
            BackgroundNormal=59,66,82
            ForegroundInactive=143,152,170
            ForegroundNormal=216,222,233

            [Colors:Header][Inactive]
            BackgroundNormal=1,2,3

            [General]
            AccentColor=136,192,208
            Name[de]=Nord
            fixed[$e]=JetBrainsMono
            """;

    private static Map<String, Map<String, String>> parse(String... texts) {
        Map<String, Map<String, String>> groups = new HashMap<>();
        for (String text : texts) {
            KdeColorScheme.parse(text, groups);
        }
        return groups;
    }

    @Test
    void readsPlasmasColours() {
        DesktopColors c = KdeColorScheme.colors(parse(NORD)).orElseThrow();
        assertEquals(new Color(59, 66, 82), c.background());
        assertEquals(new Color(46, 52, 64), c.viewBackground());
        assertEquals(new Color(136, 192, 208), c.accent());
        assertNull(c.tooltipBackground(), "not in the scheme");
        assertTrue(c.dark());
        Map<String, String> d = c.flatLafDefaults();
        assertEquals("#3b4252", d.get("@background"));
        assertEquals("#88c0d0", d.get("@accentColor"));
        assertEquals("#323946", d.get("Table.alternateRowColor"));
        assertFalse(d.containsKey("ToolTip.background"));
    }

    @Test
    void parsesKConfigSyntax() {
        Map<String, Map<String, String>> g = parse(NORD);
        assertEquals("1,2,3", g.get("Colors:Header/Inactive").get("BackgroundNormal"));
        assertFalse(g.get("General").containsKey("fixed"), "keys with flags or locales are skipped");
        assertFalse(g.get("General").containsKey("Name"));
    }

    @Test
    void laterFilesOverride() {
        DesktopColors c = KdeColorScheme.colors(parse(NORD, """
                [Colors:Window]
                BackgroundNormal=239,240,241
                """)).orElseThrow();
        assertEquals(new Color(239, 240, 241), c.background());
        assertFalse(c.dark());
    }

    @Test
    void accentFallsBackToFocusDecoration() {
        DesktopColors c = KdeColorScheme.colors(parse(NORD.replace("AccentColor=136,192,208", ""))).orElseThrow();
        assertEquals(new Color(136, 192, 208), c.accent());
    }

    @Test
    void noSchemeWithoutWindowColours() {
        assertTrue(KdeColorScheme.colors(parse("[General]\nAccentColor=1,2,3\n")).isEmpty());
    }

    @Test
    void parsesColours() {
        assertEquals(new Color(1, 2, 3), KdeColorScheme.parseColor("1,2,3"));
        assertEquals(new Color(1, 2, 3), KdeColorScheme.parseColor("1, 2, 3, 255"));
        assertEquals(new Color(0x88c0d0), KdeColorScheme.parseColor("#88c0d0"));
        assertNull(KdeColorScheme.parseColor("300,0,0"));
        assertNull(KdeColorScheme.parseColor("blue"));
        assertNull(KdeColorScheme.parseColor(null));
    }

    @Test
    void schemeOnlyForTheSameKind() {
        DesktopColors nord = KdeColorScheme.colors(parse(NORD)).orElseThrow();
        assertEquals(nord.flatLafDefaults(), ThemeSwitcher.colorsFor(true, nord));
        assertNull(ThemeSwitcher.colorsFor(false, nord), "a dark scheme isn't used for light");
        assertNull(ThemeSwitcher.colorsFor(true, null));
    }
}
