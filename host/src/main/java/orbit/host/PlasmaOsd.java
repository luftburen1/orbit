package orbit.host;

import java.io.IOException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import orbit.protocol.Message;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.interfaces.DBusInterface;

/**
 * Plasma's on-screen display, the popup volume and brightness changes show: Orbit shows what a client's TV remote did
 * with it, e.g. "Mouse mode". Plasma puts it on the screen in focus, which Orbit keeps on Sunshine's monitor while the
 * remote is used, so it appears in the stream.
 */
final class PlasmaOsd implements HostDesktop.Notices, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(PlasmaOsd.class.getName());
    private static final String PLASMASHELL = "org.kde.plasmashell";

    @DBusInterfaceName("org.kde.osdService")
    public interface OsdService extends DBusInterface {
        void showText(String icon, String text);
    }

    /** What the popup shows: a freedesktop icon name and a short text. */
    record Notice(String icon, String text) {
    }

    private final DBusConnection bus;

    private PlasmaOsd(DBusConnection bus) {
        this.bus = bus;
    }

    /** Connects on the session bus; empty when Plasma's shell isn't running. */
    static Optional<PlasmaOsd> connect() {
        try {
            Optional<PlasmaOsd> osd = SessionBus.whereRunning(PLASMASHELL).map(PlasmaOsd::new);
            if (osd.isPresent()) {
                LOG.info("Plasma found: what TV remotes do during streams is shown on the streamed monitor");
            }
            return osd;
        } catch (DBusException | DBusExecutionException | IOException e) {
            LOG.log(Level.INFO, "cannot show on-screen notices (" + e.getMessage() + ")", e);
            return Optional.empty();
        }
    }

    /** The notice for a {@link Message.RemoteAction}; empty for actions this host doesn't know. */
    static Optional<Notice> notice(String action) {
        return Optional.ofNullable(switch (action == null ? "" : action) {
            case Message.RemoteAction.MOUSE_MODE -> new Notice("input-mouse", "Mouse mode");
            case Message.RemoteAction.KEY_MODE -> new Notice("input-keyboard", "Key mode");
            case Message.RemoteAction.STOP -> new Notice("media-playback-stop", "Stopping the stream");
            default -> null;
        });
    }

    @Override
    public void remoteAction(String action) {
        notice(action).ifPresent(this::show);
    }

    void show(Notice notice) {
        try {
            bus.getRemoteObject(PLASMASHELL, "/org/kde/osdService", OsdService.class)
                    .showText(notice.icon(), notice.text());
        } catch (DBusException | DBusExecutionException e) {
            // Plasma's shell may be restarting; the notice is only a nicety.
            LOG.log(Level.FINE, "cannot show \"" + notice.text() + "\"", e);
        }
    }

    @Override
    public void close() {
        SessionBus.close(bus);
    }
}
