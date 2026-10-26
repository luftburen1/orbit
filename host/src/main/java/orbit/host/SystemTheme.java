package orbit.host;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import orbit.protocol.Commands;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

/**
 * Whether the operating system prefers a dark theme: the desktop portal on Linux, the registry on Windows,
 * {@code defaults} on macOS. Only Linux reports changes while Orbit runs.
 */
final class SystemTheme {

    private static final Logger LOG = Logger.getLogger(SystemTheme.class.getName());
    private static final String OS = System.getProperty("os.name", "").toLowerCase();

    private SystemTheme() {
    }

    /** True for dark, false for light, empty if the system doesn't say. */
    static Optional<Boolean> prefersDark() {
        try {
            if (OS.startsWith("windows")) {
                return fromWindowsRegistry(run("reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme"));
            }
            if (OS.startsWith("mac")) {
                // Prints "Dark" in dark mode; in light mode the key doesn't exist and it prints an error.
                return Optional.of(run("defaults", "read", "-g", "AppleInterfaceStyle").strip().equals("Dark"));
            }
            if (OS.contains("linux") && System.getenv("DBUS_SESSION_BUS_ADDRESS") != null) {
                try (DBusConnection bus = DBusConnectionBuilder.forSessionBus().withShared(false).build()) {
                    return fromPortal(readColorScheme(bus));
                }
            }
        } catch (IOException | DBusException | DBusExecutionException e) {
            LOG.log(Level.FINE, "cannot read the system's dark/light setting", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }

    /**
     * Calls {@code onChange} (on a D-Bus thread) whenever the desktop switches between dark and light. Linux only;
     * does nothing elsewhere or without a desktop portal. The connection stays open while Orbit runs.
     */
    static void watch(Consumer<Boolean> onChange) {
        if (!OS.contains("linux") || System.getenv("DBUS_SESSION_BUS_ADDRESS") == null) {
            return;
        }
        try {
            DBusConnection bus = DBusConnectionBuilder.forSessionBus().withShared(false).build();
            bus.addSigHandler(PortalSettings.SettingChanged.class, signal -> {
                if (PortalSettings.APPEARANCE.equals(signal.namespace)
                        && PortalSettings.COLOR_SCHEME.equals(signal.key)) {
                    fromPortal(signal.value).ifPresent(onChange);
                }
            });
        } catch (DBusException | DBusExecutionException e) {
            LOG.log(Level.FINE, "cannot follow the desktop's dark/light setting", e);
        }
    }

    private static Variant<?> readColorScheme(DBusConnection bus) throws DBusException {
        PortalSettings settings = bus.getRemoteObject(PortalSettings.BUS_NAME, PortalSettings.PATH,
                PortalSettings.class);
        try {
            return settings.ReadOne(PortalSettings.APPEARANCE, PortalSettings.COLOR_SCHEME);
        } catch (DBusExecutionException e) {
            return settings.Read(PortalSettings.APPEARANCE, PortalSettings.COLOR_SCHEME);
        }
    }

    /** The portal's {@code color-scheme}: 1 prefers dark, 2 prefers light, 0 no preference. */
    static Optional<Boolean> fromPortal(Object value) {
        while (value instanceof Variant<?> v) {
            value = v.getValue();
        }
        long scheme = value instanceof UInt32 u ? u.longValue() : value instanceof Number n ? n.longValue() : 0;
        return scheme == 1 ? Optional.of(true) : scheme == 2 ? Optional.of(false) : Optional.empty();
    }

    private static final Pattern REG_VALUE = Pattern.compile("AppsUseLightTheme\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)");

    /** {@code reg query … /v AppsUseLightTheme}: 0 means dark. */
    static Optional<Boolean> fromWindowsRegistry(String output) {
        Matcher m = REG_VALUE.matcher(output);
        return m.find() ? Optional.of(Long.parseLong(m.group(1), 16) == 0) : Optional.empty();
    }

    private static String run(String... command) throws IOException, InterruptedException {
        return Commands.run(List.of(command), Duration.ofSeconds(5)).output();
    }
}
