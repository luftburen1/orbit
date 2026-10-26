package orbit.host;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;

/**
 * Plasma's colour scheme, from {@code kdeglobals}: the {@code Colors:*} groups that {@code plasma-apply-colorscheme}
 * and System Settings write there, and {@code [General] AccentColor}. Plasma announces changes with KConfig's
 * {@code org.kde.kconfig.notify.ConfigChanged} on {@code /kdeglobals}, as KDE's own apps follow them.
 */
final class KdeColorScheme implements DesktopColors.Source, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(KdeColorScheme.class.getName());

    /** User's file last, so it overrides the system's. */
    private final List<Path> files;
    private DBusConnection bus;

    private KdeColorScheme(List<Path> files) {
        this.files = files;
    }

    /** Empty when there's no {@code kdeglobals} at all, e.g. off Plasma. */
    static Optional<KdeColorScheme> find() {
        List<Path> files = new ArrayList<>();
        String dirs = System.getenv("XDG_CONFIG_DIRS");
        List<String> system = new ArrayList<>(List.of((dirs == null || dirs.isBlank() ? "/etc/xdg" : dirs).split(":")));
        // Earlier directories are more important, so they're read later.
        for (int i = system.size() - 1; i >= 0; i--) {
            files.add(Path.of(system.get(i), "kdeglobals"));
        }
        String home = System.getenv("XDG_CONFIG_HOME");
        files.add((home == null || home.isBlank() ? Path.of(System.getProperty("user.home"), ".config") : Path.of(home))
                .resolve("kdeglobals"));
        List<Path> existing = files.stream().filter(Files::isRegularFile).toList();
        return existing.isEmpty() ? Optional.empty() : Optional.of(new KdeColorScheme(existing));
    }

    @Override
    public Optional<DesktopColors> read() {
        Map<String, Map<String, String>> groups = new HashMap<>();
        for (Path file : files) {
            try {
                parse(Files.readString(file), groups);
            } catch (IOException e) {
                LOG.log(Level.FINE, "cannot read " + file, e);
            }
        }
        return colors(groups);
    }

    @Override
    public synchronized void watch(Runnable changed) {
        if (bus != null || System.getenv("DBUS_SESSION_BUS_ADDRESS") == null) {
            return;
        }
        try {
            bus = DBusConnectionBuilder.forSessionBus().withShared(false).build();
            bus.addSigHandler(KConfigNotify.ConfigChanged.class, signal -> {
                if ("/kdeglobals".equals(signal.getPath()) && signal.groups.keySet().stream()
                        .anyMatch(g -> g.startsWith("Colors:") || g.equals("General"))) {
                    changed.run();
                }
            });
        } catch (DBusException | DBusExecutionException e) {
            LOG.log(Level.FINE, "cannot follow Plasma's colour scheme", e);
        }
    }

    @Override
    public synchronized void close() {
        if (bus != null) {
            SessionBus.close(bus);
            bus = null;
        }
    }

    /** Adds {@code kdeglobals}' groups and keys to {@code groups}, overriding what's there. */
    static void parse(String text, Map<String, Map<String, String>> groups) {
        Map<String, String> group = null;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[")) {
                // Nested groups are written [Colors:Header][Inactive]; flags like [$i] aren't groups.
                String name = line.replaceAll("\\[\\$[a-z]+]", "");
                name = name.substring(1, Math.max(1, name.lastIndexOf(']'))).replace("][", "/");
                group = groups.computeIfAbsent(name, k -> new HashMap<>());
                continue;
            }
            int eq = line.indexOf('=');
            if (group != null && eq > 0) {
                // A key can carry flags or a locale: Key[$e]=…, Name[de]=…; only plain keys matter here.
                String key = line.substring(0, eq).strip();
                if (!key.contains("[")) {
                    group.put(key, line.substring(eq + 1).strip());
                }
            }
        }
    }

    static Optional<DesktopColors> colors(Map<String, Map<String, String>> groups) {
        Color background = color(groups, "Colors:Window", "BackgroundNormal");
        Color foreground = color(groups, "Colors:Window", "ForegroundNormal");
        if (background == null || foreground == null) {
            return Optional.empty();
        }
        Color accent = color(groups, "General", "AccentColor");
        if (accent == null) {
            accent = color(groups, "Colors:View", "DecorationFocus");
        }
        return Optional.of(new DesktopColors(background, foreground,
                color(groups, "Colors:Window", "ForegroundInactive"),
                color(groups, "Colors:View", "BackgroundNormal"),
                color(groups, "Colors:View", "ForegroundNormal"),
                color(groups, "Colors:View", "BackgroundAlternate"),
                color(groups, "Colors:Button", "BackgroundNormal"),
                color(groups, "Colors:Button", "ForegroundNormal"),
                color(groups, "Colors:Selection", "BackgroundNormal"),
                color(groups, "Colors:Selection", "ForegroundNormal"),
                accent,
                color(groups, "Colors:Tooltip", "BackgroundNormal"),
                color(groups, "Colors:Tooltip", "ForegroundNormal")));
    }

    private static Color color(Map<String, Map<String, String>> groups, String group, String key) {
        Map<String, String> g = groups.get(group);
        return g == null ? null : parseColor(g.get(key));
    }

    /** KConfig's colours: {@code r,g,b}, {@code r,g,b,a} or {@code #rrggbb}; null if it's neither. */
    static Color parseColor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (value.startsWith("#") && value.length() == 7) {
                return new Color(Integer.parseInt(value.substring(1), 16));
            }
            String[] parts = value.split(",");
            if (parts.length == 3 || parts.length == 4) {
                return new Color(Integer.parseInt(parts[0].strip()), Integer.parseInt(parts[1].strip()),
                        Integer.parseInt(parts[2].strip()));
            }
        } catch (IllegalArgumentException e) {
            // not a colour
        }
        return null;
    }

    /** KConfig's change notifications, which KConfigWatcher in KDE's apps listens to. */
    @DBusInterfaceName("org.kde.kconfig.notify")
    public interface KConfigNotify extends DBusInterface {
        class ConfigChanged extends DBusSignal {
            /** Changed groups (nested ones as a path of names) and their changed keys. */
            public final Map<String, List<String>> groups;

            public ConfigChanged(String path, Map<String, List<String>> groups) throws DBusException {
                super(path, groups);
                this.groups = groups;
            }
        }
    }
}
