package orbit.host;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.Variant;

/**
 * The desktop portal's settings, which say whether the desktop prefers dark or light (KDE Plasma, GNOME and others).
 * Spec: https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Settings.html
 */
@DBusInterfaceName("org.freedesktop.portal.Settings")
public interface PortalSettings extends DBusInterface {

    String BUS_NAME = "org.freedesktop.portal.Desktop";
    String PATH = "/org/freedesktop/portal/desktop";
    String APPEARANCE = "org.freedesktop.appearance";
    String COLOR_SCHEME = "color-scheme";

    /** Since version 2 of the interface. */
    Variant<?> ReadOne(String namespace, String key);

    /** Deprecated, but the only one older portals have; wraps the value in a second variant. */
    Variant<?> Read(String namespace, String key);

    class SettingChanged extends DBusSignal {
        public final String namespace;
        public final String key;
        public final Variant<?> value;

        public SettingChanged(String path, String namespace, String key, Variant<?> value) throws DBusException {
            super(path, namespace, key, value);
            this.namespace = namespace;
            this.key = key;
            this.value = value;
        }
    }
}
