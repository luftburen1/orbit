package orbit.host;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Light or dark, following the system unless Settings ▸ General says otherwise, in the desktop's colour scheme when
 * it's of the same kind (a dark scheme for dark), else in FlatLaf's own colours. Event thread only.
 */
final class ThemeSwitcher {

    private static final Logger LOG = Logger.getLogger(ThemeSwitcher.class.getName());

    enum Choice {
        SYSTEM, LIGHT, DARK;

        /** As stored in {@code host.json}; null and unknown values mean {@link #SYSTEM}. */
        static Choice parse(String value) {
            if (value != null) {
                for (Choice c : values()) {
                    if (c.name().equalsIgnoreCase(value)) {
                        return c;
                    }
                }
            }
            return SYSTEM;
        }

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final DesktopColors.Source colors;
    private Choice choice;
    private boolean systemDark;
    private DesktopColors scheme;
    /** The colours the look and feel was last set up with, null for FlatLaf's own. */
    private Map<String, String> applied;

    /**
     * @param systemDark what the system prefers right now ({@link SystemTheme#prefersDark}, light if unknown)
     * @param colors     the desktop's colour scheme, or null if it has none
     * @param scheme     what {@code colors} read right now, or null
     */
    ThemeSwitcher(Choice choice, boolean systemDark, DesktopColors.Source colors, DesktopColors scheme) {
        this.choice = choice;
        this.systemDark = systemDark;
        this.colors = colors;
        this.scheme = scheme;
    }

    /** Sets the look and feel, then follows the system's changes where it reports them. */
    void install() {
        apply();
        SystemTheme.watch(dark -> SwingUtilities.invokeLater(() -> {
            systemDark = dark;
            if (choice == Choice.SYSTEM) {
                LOG.info(() -> "the system switched to " + (dark ? "dark" : "light"));
            }
            apply();
        }));
        if (colors != null) {
            colors.watch(() -> {
                DesktopColors read = colors.read().orElse(null);
                SwingUtilities.invokeLater(() -> {
                    if (!Objects.equals(read, scheme)) {
                        scheme = read;
                        LOG.info("the desktop's colour scheme changed");
                        apply();
                    }
                });
            });
        }
    }

    Choice choice() {
        return choice;
    }

    void choose(Choice choice) {
        this.choice = choice;
        apply();
    }

    static boolean dark(Choice choice, boolean systemDark) {
        return switch (choice) {
            case SYSTEM -> systemDark;
            case LIGHT -> false;
            case DARK -> true;
        };
    }

    /** The scheme's colours for FlatLaf if it's as dark as wanted, else null: FlatLaf's own. */
    static Map<String, String> colorsFor(boolean dark, DesktopColors scheme) {
        return scheme != null && scheme.dark() == dark ? scheme.flatLafDefaults() : null;
    }

    /** Switches the look and feel if needed and restyles every open window. */
    private void apply() {
        boolean dark = dark(choice, systemDark);
        Map<String, String> wanted = colorsFor(dark, scheme);
        if (UIManager.getLookAndFeel() instanceof FlatLaf && FlatLaf.isLafDark() == dark
                && Objects.equals(wanted, applied)) {
            return;
        }
        FlatLaf.setGlobalExtraDefaults(wanted);
        applied = wanted;
        if (dark) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
        FlatLaf.updateUI();
    }
}
