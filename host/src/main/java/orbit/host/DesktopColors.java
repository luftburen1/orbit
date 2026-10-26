package orbit.host;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The desktop's colour scheme, which Orbit's light or dark look and feel takes on top of FlatLaf's own: windows,
 * views (lists, tables, text fields), buttons, selection and accent. FlatLaf derives everything else (hover, borders,
 * disabled shades) from these. Null parts keep FlatLaf's.
 */
record DesktopColors(Color background, Color foreground, Color inactiveForeground, Color viewBackground,
        Color viewForeground, Color viewAlternate, Color buttonBackground, Color buttonForeground,
        Color selectionBackground, Color selectionForeground, Color accent, Color tooltipBackground,
        Color tooltipForeground) {

    DesktopColors {
        if (background == null || foreground == null) {
            throw new IllegalArgumentException("a colour scheme needs a background and a foreground");
        }
    }

    /** Whether it's a dark scheme: its windows' background is darker than mid-grey. */
    boolean dark() {
        return luminance(background) < 0.5;
    }

    /** FlatLaf's variables and keys for {@link com.formdev.flatlaf.FlatLaf#setGlobalExtraDefaults}. */
    Map<String, String> flatLafDefaults() {
        Map<String, String> d = new LinkedHashMap<>();
        put(d, background, "@background");
        put(d, foreground, "@foreground");
        put(d, inactiveForeground, "@disabledForeground");
        put(d, viewBackground, "@componentBackground", "Table.background", "List.background", "Tree.background");
        put(d, viewForeground, "Table.foreground", "List.foreground", "Tree.foreground", "TextField.foreground");
        put(d, viewAlternate, "Table.alternateRowColor");
        put(d, buttonBackground, "@buttonBackground");
        put(d, buttonForeground, "Button.foreground", "ToggleButton.foreground");
        put(d, accent, "@accentColor");
        put(d, selectionBackground, "@selectionBackground");
        put(d, selectionForeground, "@selectionForeground");
        // FlatLaf's own shade of an unfocused list's selection turns muddy in other schemes: half the selection.
        if (selectionBackground != null) {
            put(d, mix(selectionBackground, viewBackground != null ? viewBackground : background),
                    "@selectionInactiveBackground");
        }
        put(d, tooltipBackground, "ToolTip.background");
        put(d, tooltipForeground, "ToolTip.foreground");
        return d;
    }

    private static void put(Map<String, String> d, Color color, String... keys) {
        if (color != null) {
            for (String key : keys) {
                d.put(key, hex(color));
            }
        }
    }

    /** Halfway between {@code a} and {@code b}. */
    static Color mix(Color a, Color b) {
        return new Color((a.getRed() + b.getRed()) / 2, (a.getGreen() + b.getGreen()) / 2,
                (a.getBlue() + b.getBlue()) / 2);
    }

    static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** Relative luminance (sRGB), 0 for black to 1 for white. */
    static double luminance(Color c) {
        return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
    }

    private static double linear(int channel) {
        double v = channel / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** Where the desktop's colour scheme comes from, and when it changes. */
    interface Source {
        /** The scheme as it is now, or empty if the desktop doesn't say. Blocking but quick. */
        Optional<DesktopColors> read();

        /** Calls {@code changed} (on any thread) whenever the scheme may have changed. */
        void watch(Runnable changed);
    }
}
