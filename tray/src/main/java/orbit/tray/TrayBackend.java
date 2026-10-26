package orbit.tray;

import java.util.List;
import java.util.Optional;
import orbit.tray.sni.SniTray;

/** A tray icon with a menu, a tooltip and notifications. Implementations run menu actions on the event thread. */
public interface TrayBackend {

    /**
     * @param id          the tray item's id, e.g. "orbit"
     * @param title       the full name, e.g. in the desktop's tray settings
     * @param name        the short name: the tooltip's heading and the notifications' sender
     * @param activate    what clicking the icon does; null opens the menu instead, where the tray allows it
     * @param menuOpening runs on the event thread when the menu is about to show, if the backend can tell
     */
    record Options(String id, String title, String name, Runnable activate, Runnable menuOpening) {
    }

    /**
     * Installs the tray icon: the D-Bus StatusNotifierItem where the desktop offers one (KDE, GNOME with the
     * AppIndicator extension), else Java's own tray. Empty if neither is available.
     */
    static Optional<TrayBackend> install(Options options) {
        return SniTray.install(options).or(() -> AwtTray.install(options));
    }

    /** Replaces the whole menu. Called on the event thread. */
    void setMenu(List<TrayMenu.Entry> menu);

    void setToolTip(String text);

    /** Shows whether something streams, on the icon. Called on the event thread. */
    void setStreaming(boolean streaming);

    void notify(String title, String message);
}
