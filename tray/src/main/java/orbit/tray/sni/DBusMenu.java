package orbit.tray.sni;

import java.util.List;
import java.util.Map;
import org.freedesktop.dbus.Struct;
import org.freedesktop.dbus.Tuple;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.Position;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

/**
 * A menu exported over D-Bus, which the shell renders as the tray icon's context menu.
 * Spec: libdbusmenu's {@code com.canonical.dbusmenu.xml}.
 */
@DBusInterfaceName("com.canonical.dbusmenu")
public interface DBusMenu extends DBusInterface {

    String INTERFACE = "com.canonical.dbusmenu";

    Pair<UInt32, Layout> GetLayout(int parentId, int recursionDepth, List<String> propertyNames);

    List<ItemProperties> GetGroupProperties(List<Integer> ids, List<String> propertyNames);

    Variant<?> GetProperty(int id, String name);

    void Event(int id, String eventId, Variant<?> data, UInt32 timestamp);

    List<Integer> EventGroup(List<EventInfo> events);

    boolean AboutToShow(int id);

    Pair<List<Integer>, List<Integer>> AboutToShowGroup(List<Integer> ids);

    /** The menu below {@code parent} changed; the shell fetches it again. */
    class LayoutUpdated extends DBusSignal {
        public final UInt32 revision;
        public final int parent;

        public LayoutUpdated(String path, UInt32 revision, int parent) throws DBusException {
            super(path, revision, parent);
            this.revision = revision;
            this.parent = parent;
        }
    }

    /** A menu item and its children. D-Bus type {@code (ia{sv}av)}; each child is a variant holding a Layout. */
    final class Layout extends Struct {
        public static final String SIGNATURE = "(ia{sv}av)";

        @Position(0)
        public final int id;
        @Position(1)
        public final Map<String, Variant<?>> properties;
        @Position(2)
        public final List<Variant<?>> children;

        public Layout(int id, Map<String, Variant<?>> properties, List<Variant<?>> children) {
            this.id = id;
            this.properties = properties;
            this.children = children;
        }
    }

    /** D-Bus type {@code (ia{sv})}. */
    final class ItemProperties extends Struct {
        @Position(0)
        public final int id;
        @Position(1)
        public final Map<String, Variant<?>> properties;

        public ItemProperties(int id, Map<String, Variant<?>> properties) {
            this.id = id;
            this.properties = properties;
        }
    }

    /** D-Bus type {@code (isvu)}. */
    final class EventInfo extends Struct {
        @Position(0)
        public final int id;
        @Position(1)
        public final String eventId;
        @Position(2)
        public final Variant<?> data;
        @Position(3)
        public final UInt32 timestamp;

        public EventInfo(int id, String eventId, Variant<?> data, UInt32 timestamp) {
            this.id = id;
            this.eventId = eventId;
            this.data = data;
            this.timestamp = timestamp;
        }
    }

    /** Two return values. */
    final class Pair<A, B> extends Tuple {
        @Position(0)
        public final A first;
        @Position(1)
        public final B second;

        public Pair(A first, B second) {
            this.first = first;
            this.second = second;
        }
    }
}
