package orbit.host;

import java.io.IOException;
import java.util.Optional;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.interfaces.DBus;

/** Private connections to the desktop's session D-Bus, for talking to Plasma. */
final class SessionBus {

    private SessionBus() {
    }

    /**
     * A connection of its own, if this is Linux with a session bus on which {@code service} runs; empty otherwise.
     * The caller closes it.
     */
    static Optional<DBusConnection> whereRunning(String service) throws DBusException, IOException {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")
                || System.getenv("DBUS_SESSION_BUS_ADDRESS") == null) {
            return Optional.empty();
        }
        DBusConnection bus = DBusConnectionBuilder.forSessionBus().withShared(false).build();
        try {
            DBus dbus = bus.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus.class);
            if (dbus.NameHasOwner(service)) {
                return Optional.of(bus);
            }
        } catch (DBusException | DBusExecutionException e) {
            close(bus);
            throw e;
        }
        close(bus);
        return Optional.empty();
    }

    static void close(DBusConnection bus) {
        try {
            bus.close();
        } catch (IOException ignored) {
            // nothing left to do with it
        }
    }
}
