package orbit.tray.sni;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.interfaces.DBusInterface;

/** The desktop shell's registry of tray icons. */
@DBusInterfaceName("org.kde.StatusNotifierWatcher")
public interface StatusNotifierWatcher extends DBusInterface {

    String BUS_NAME = "org.kde.StatusNotifierWatcher";
    String PATH = "/StatusNotifierWatcher";

    void RegisterStatusNotifierItem(String service);
}
