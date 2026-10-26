package orbit.tray;

import java.awt.AWTException;
import java.awt.CheckboxMenuItem;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Tray icon through {@code java.awt.SystemTray}. Works on Windows and macOS. On Linux it uses the old XEmbed tray,
 * whose menu does not work on Wayland desktops, so {@link orbit.tray.sni.SniTray} is tried first there. The menu is
 * on the right button; clicking the icon runs {@link Options#activate}, if any.
 */
final class AwtTray implements TrayBackend {

    private static final Logger LOG = Logger.getLogger(AwtTray.class.getName());

    private final TrayIcon icon;
    private final Options options;
    private boolean streaming;

    private AwtTray(TrayIcon icon, Options options) {
        this.icon = icon;
        this.options = options;
    }

    static Optional<TrayBackend> install(Options options) {
        if (!SystemTray.isSupported()) {
            return Optional.empty();
        }
        SystemTray tray = SystemTray.getSystemTray();
        TrayIcon icon = new TrayIcon(TrayIcons.trayIcon(iconSize(tray), false), options.name(), new PopupMenu());
        icon.setImageAutoSize(true);
        if (options.activate() != null) {
            icon.addActionListener(e -> options.activate().run());
        }
        icon.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (options.menuOpening() != null) {
                    options.menuOpening().run();
                }
            }
        });
        try {
            tray.add(icon);
        } catch (AWTException e) {
            LOG.warning("cannot add tray icon: " + e.getMessage());
            return Optional.empty();
        }
        AwtTray awtTray = new AwtTray(icon, options);
        awtTray.setMenu(List.of());
        return Optional.of(awtTray);
    }

    @Override
    public void setMenu(List<TrayMenu.Entry> entries) {
        PopupMenu menu = new PopupMenu();
        fill(menu, entries);
        icon.setPopupMenu(menu);
    }

    private static void fill(Menu menu, List<TrayMenu.Entry> entries) {
        for (TrayMenu.Entry entry : entries) {
            switch (entry) {
                case TrayMenu.Separator s -> menu.addSeparator();
                case TrayMenu.Item item when !item.children().isEmpty() -> {
                    Menu sub = new Menu(item.label());
                    sub.setEnabled(item.enabled());
                    fill(sub, item.children());
                    menu.add(sub);
                }
                case TrayMenu.Item item when item.checked() != null -> {
                    CheckboxMenuItem mi = new CheckboxMenuItem(item.label(), item.checked());
                    mi.setEnabled(item.enabled() && item.action() != null);
                    if (item.action() != null) {
                        mi.addItemListener(e -> item.action().run());
                    }
                    menu.add(mi);
                }
                case TrayMenu.Item item -> {
                    MenuItem mi = new MenuItem(item.label());
                    mi.setEnabled(item.enabled() && item.action() != null);
                    if (item.action() != null) {
                        mi.addActionListener(e -> item.action().run());
                    }
                    menu.add(mi);
                }
            }
        }
    }

    private static int iconSize(SystemTray tray) {
        return Math.max(tray.getTrayIconSize().height, 16);
    }

    @Override
    public void setStreaming(boolean streaming) {
        if (streaming == this.streaming) {
            return;
        }
        this.streaming = streaming;
        icon.setImage(TrayIcons.trayIcon(iconSize(SystemTray.getSystemTray()), streaming));
    }

    @Override
    public void setToolTip(String text) {
        icon.setToolTip(options.name() + ": " + text);
    }

    @Override
    public void notify(String title, String message) {
        icon.displayMessage(title, message, TrayIcon.MessageType.INFO);
    }
}
