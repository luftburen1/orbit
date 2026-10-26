package orbit.tray.sni;

import java.util.List;
import org.freedesktop.dbus.Struct;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.Position;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;

/**
 * The tray icon as a desktop shell sees it (KDE Plasma natively; GNOME with the AppIndicator extension).
 * Spec: https://www.freedesktop.org/wiki/Specifications/StatusNotifierItem/
 */
@DBusInterfaceName("org.kde.StatusNotifierItem")
public interface StatusNotifierItem extends DBusInterface {

    String INTERFACE = "org.kde.StatusNotifierItem";

    void ContextMenu(int x, int y);

    /** Primary (usually left) click. */
    void Activate(int x, int y);

    /** Middle click. */
    void SecondaryActivate(int x, int y);

    void Scroll(int delta, String orientation);

    /** One icon size, ARGB32 pixels in network byte order. D-Bus type {@code (iiay)}. */
    final class Pixmap extends Struct {
        @Position(0)
        public final int width;
        @Position(1)
        public final int height;
        @Position(2)
        public final byte[] data;

        public Pixmap(int width, int height, byte[] data) {
            this.width = width;
            this.height = height;
            this.data = data;
        }
    }

    /** D-Bus type {@code (sa(iiay)ss)}. */
    final class ToolTip extends Struct {
        @Position(0)
        public final String iconName;
        @Position(1)
        public final List<Pixmap> iconPixmap;
        @Position(2)
        public final String title;
        @Position(3)
        public final String description;

        public ToolTip(String iconName, List<Pixmap> iconPixmap, String title, String description) {
            this.iconName = iconName;
            this.iconPixmap = iconPixmap;
            this.title = title;
            this.description = description;
        }
    }

    class NewToolTip extends DBusSignal {
        public NewToolTip(String path) throws DBusException {
            super(path);
        }
    }

    class NewIcon extends DBusSignal {
        public NewIcon(String path) throws DBusException {
            super(path);
        }
    }

    class NewTitle extends DBusSignal {
        public NewTitle(String path) throws DBusException {
            super(path);
        }
    }

    class NewStatus extends DBusSignal {
        public final String status;

        public NewStatus(String path, String status) throws DBusException {
            super(path, status);
            this.status = status;
        }
    }
}
