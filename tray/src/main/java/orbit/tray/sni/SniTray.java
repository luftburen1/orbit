package orbit.tray.sni;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import orbit.protocol.Logo;
import orbit.tray.TrayBackend;
import orbit.tray.TrayIcons;
import orbit.tray.TrayMenu;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.interfaces.DBus;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

/**
 * Tray icon as a D-Bus StatusNotifierItem with a {@code com.canonical.dbusmenu} menu, the protocol KDE Plasma (and
 * GNOME's AppIndicator extension) use natively. Unlike the AWT tray, its menu works on Wayland.
 */
public final class SniTray implements TrayBackend {

    private static final Logger LOG = Logger.getLogger(SniTray.class.getName());

    private static final String ITEM_PATH = "/StatusNotifierItem";
    private static final String MENU_PATH = "/MenuBar";
    private static final String PIXMAPS = "a(iiay)";
    private static final int[] ICON_SIZES = {16, 22, 24, 32, 48, 64};

    private final DBusConnection bus;
    private final String busName;
    private final Options options;
    private volatile List<StatusNotifierItem.Pixmap> icon = iconPixmaps(false);
    private boolean streaming;
    /** Notifications and signals are sent off the event thread so a slow bus never stalls the UI. */
    private final ExecutorService sender = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("orbit-tray-dbus").factory());
    private volatile String toolTip = "";
    private final MenuTree menuTree = new MenuTree();
    /** The menu the shell reads; replaced as a whole. */
    private volatile MenuTree.Snapshot menu;
    private final AtomicLong revision = new AtomicLong(1);

    private SniTray(DBusConnection bus, String busName, Options options) {
        this.bus = bus;
        this.busName = busName;
        this.options = options;
        this.menu = menuTree.build(List.of());
    }

    /** Registers the tray icon with the desktop's StatusNotifierWatcher, if there is one on the session bus. */
    public static Optional<TrayBackend> install(Options options) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")
                || System.getenv("DBUS_SESSION_BUS_ADDRESS") == null) {
            return Optional.empty();
        }
        DBusConnection bus = null;
        try {
            bus = DBusConnectionBuilder.forSessionBus().withShared(false).build();
            String busName = "org.kde.StatusNotifierItem-" + ProcessHandle.current().pid() + "-1";
            SniTray tray = new SniTray(bus, busName, options);
            bus.exportObject(ITEM_PATH, tray.new Item());
            bus.exportObject(MENU_PATH, tray.new Menu());
            bus.requestBusName(busName);
            tray.register();
            tray.reregisterWhenWatcherRestarts();
            LOG.info("tray icon registered with the desktop via StatusNotifierItem");
            return Optional.of(tray);
        } catch (DBusException | DBusExecutionException e) {
            LOG.log(Level.INFO, "no StatusNotifierItem tray on this desktop (" + e.getMessage() + ")", e);
            if (bus != null) {
                try {
                    bus.close();
                } catch (Exception ignored) {
                    // already failing
                }
            }
            return Optional.empty();
        }
    }

    private void register() throws DBusException {
        bus.getRemoteObject(StatusNotifierWatcher.BUS_NAME, StatusNotifierWatcher.PATH, StatusNotifierWatcher.class)
                .RegisterStatusNotifierItem(busName);
    }

    /** Plasma's watcher forgets all items when plasmashell restarts; register again when it comes back. */
    private void reregisterWhenWatcherRestarts() throws DBusException {
        bus.addSigHandler(DBus.NameOwnerChanged.class, signal -> {
            if (StatusNotifierWatcher.BUS_NAME.equals(signal.name) && !signal.newOwner.isEmpty()) {
                sender.execute(() -> {
                    try {
                        register();
                        LOG.info("tray icon registered again after the desktop's tray restarted");
                    } catch (DBusException | DBusExecutionException e) {
                        LOG.log(Level.WARNING, "cannot re-register tray icon", e);
                    }
                });
            }
        });
    }

    @Override
    public void setMenu(List<TrayMenu.Entry> entries) {
        MenuTree.Snapshot updated = menuTree.build(entries);
        menu = updated;
        long rev = revision.incrementAndGet();
        // Plasma refetches only the level a signal names, so each submenu gets its own.
        sender.execute(() -> {
            try {
                for (int id : updated.menuIds()) {
                    bus.sendMessage(new DBusMenu.LayoutUpdated(MENU_PATH, new UInt32(rev), id));
                }
            } catch (DBusException e) {
                LOG.log(Level.FINE, "cannot signal menu change", e);
            }
        });
    }

    @Override
    public void setToolTip(String text) {
        toolTip = text;
        sender.execute(() -> {
            try {
                bus.sendMessage(new StatusNotifierItem.NewToolTip(ITEM_PATH));
            } catch (DBusException e) {
                LOG.log(Level.FINE, "cannot signal tooltip change", e);
            }
        });
    }

    @Override
    public void setStreaming(boolean streaming) {
        if (streaming == this.streaming) {
            return;
        }
        this.streaming = streaming;
        icon = iconPixmaps(streaming);
        sender.execute(() -> {
            try {
                bus.sendMessage(new StatusNotifierItem.NewIcon(ITEM_PATH));
            } catch (DBusException e) {
                LOG.log(Level.FINE, "cannot signal icon change", e);
            }
        });
    }

    @Override
    public void notify(String title, String message) {
        sender.execute(() -> {
            try {
                Map<String, Variant<?>> hints = new LinkedHashMap<>();
                hints.put("image-data", new Variant<>(notificationImage(), Notifications.ImageData.SIGNATURE));
                bus.getRemoteObject(Notifications.BUS_NAME, Notifications.PATH, Notifications.class)
                        .Notify(options.name(), new UInt32(0), "", title, message, List.of(), hints, -1);
            } catch (DBusException | DBusExecutionException e) {
                LOG.log(Level.WARNING, "cannot show notification: " + message, e);
            }
        });
    }

    /** Runs a menu or click action on the Swing event thread; nothing if there's no action. */
    private static void onEdt(Runnable action) {
        if (action != null) {
            SwingUtilities.invokeLater(action);
        }
    }

    // ---- exported objects ----

    /** The icon itself: properties the shell reads, and click callbacks. */
    public final class Item implements StatusNotifierItem, Properties {

        @Override
        public String getObjectPath() {
            return ITEM_PATH;
        }

        @Override
        public void ContextMenu(int x, int y) {
            // The shell shows the exported Menu itself.
        }

        /** Left click; without an activate action, ItemIsMenu makes the shell open the menu instead. */
        @Override
        public void Activate(int x, int y) {
            onEdt(options.activate());
        }

        /** Middle click. */
        @Override
        public void SecondaryActivate(int x, int y) {
            onEdt(options.activate());
        }

        @Override
        public void Scroll(int delta, String orientation) {
        }

        @Override
        @SuppressWarnings("unchecked")
        public <A> A Get(String interfaceName, String propertyName) {
            Variant<?> value = GetAll(interfaceName).get(propertyName);
            if (value == null) {
                throw new DBusExecutionException("no property " + propertyName + " on " + interfaceName);
            }
            return (A) value;
        }

        @Override
        public <A> void Set(String interfaceName, String propertyName, A value) {
            throw new DBusExecutionException("properties are read-only");
        }

        @Override
        public Map<String, Variant<?>> GetAll(String interfaceName) {
            Map<String, Variant<?>> p = new LinkedHashMap<>();
            if (!StatusNotifierItem.INTERFACE.equals(interfaceName)) {
                return p;
            }
            p.put("Category", new Variant<>("ApplicationStatus"));
            p.put("Id", new Variant<>(options.id()));
            p.put("Title", new Variant<>(options.title()));
            p.put("Status", new Variant<>("Active"));
            p.put("WindowId", new Variant<>(0));
            p.put("IconName", new Variant<>(""));
            p.put("IconPixmap", new Variant<>(icon, PIXMAPS));
            p.put("OverlayIconName", new Variant<>(""));
            p.put("OverlayIconPixmap", new Variant<>(List.of(), PIXMAPS));
            p.put("AttentionIconName", new Variant<>(""));
            p.put("AttentionIconPixmap", new Variant<>(List.of(), PIXMAPS));
            p.put("AttentionMovieName", new Variant<>(""));
            p.put("ToolTip", new Variant<>(new StatusNotifierItem.ToolTip("", icon, options.name(), toolTip),
                    "(sa(iiay)ss)"));
            p.put("ItemIsMenu", new Variant<>(options.activate() == null));
            p.put("Menu", new Variant<>(new org.freedesktop.dbus.DBusPath(MENU_PATH)));
            return p;
        }
    }

    /** The right-click menu, as the caller set it. */
    public final class Menu implements DBusMenu, Properties {

        @Override
        public String getObjectPath() {
            return MENU_PATH;
        }

        @Override
        public Pair<UInt32, Layout> GetLayout(int parentId, int recursionDepth, List<String> propertyNames) {
            long rev = revision.get();
            return new Pair<>(new UInt32(rev), menu.layout(parentId, recursionDepth));
        }

        @Override
        public List<ItemProperties> GetGroupProperties(List<Integer> ids, List<String> propertyNames) {
            MenuTree.Snapshot m = menu;
            Collection<Integer> wanted = ids.isEmpty() ? m.byId().keySet() : ids;
            return wanted.stream().map(m.byId()::get).filter(Objects::nonNull)
                    .map(node -> new ItemProperties(node.id(), node.properties())).toList();
        }

        @Override
        public Variant<?> GetProperty(int id, String name) {
            MenuTree.Node node = menu.byId().get(id);
            Variant<?> value = node == null ? null : node.properties().get(name);
            if (value == null) {
                throw new DBusExecutionException("no property " + name + " on menu item " + id);
            }
            return value;
        }

        @Override
        public void Event(int id, String eventId, Variant<?> data, UInt32 timestamp) {
            if (!"clicked".equals(eventId)) {
                return;
            }
            MenuTree.Node node = menu.byId().get(id);
            if (node != null && node.enabled() && node.action() != null) {
                onEdt(node.action());
            }
        }

        @Override
        public List<Integer> EventGroup(List<EventInfo> events) {
            events.forEach(e -> Event(e.id, e.eventId, e.data, e.timestamp));
            return List.of();
        }

        /**
         * Always asks the shell to fetch the menu again: Plasma otherwise shows a submenu as it was the first time it
         * opened. Opening the top menu is also the cue to refresh what it shows, e.g. the apps.
         */
        @Override
        public boolean AboutToShow(int id) {
            if (id == MenuTree.ROOT) {
                onEdt(options.menuOpening());
            }
            return true;
        }

        @Override
        public Pair<List<Integer>, List<Integer>> AboutToShowGroup(List<Integer> ids) {
            if (ids.contains(MenuTree.ROOT)) {
                onEdt(options.menuOpening());
            }
            return new Pair<>(ids, List.of());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <A> A Get(String interfaceName, String propertyName) {
            Variant<?> value = GetAll(interfaceName).get(propertyName);
            if (value == null) {
                throw new DBusExecutionException("no property " + propertyName + " on " + interfaceName);
            }
            return (A) value;
        }

        @Override
        public <A> void Set(String interfaceName, String propertyName, A value) {
            throw new DBusExecutionException("properties are read-only");
        }

        @Override
        public Map<String, Variant<?>> GetAll(String interfaceName) {
            Map<String, Variant<?>> p = new LinkedHashMap<>();
            if (DBusMenu.INTERFACE.equals(interfaceName)) {
                p.put("Version", new Variant<>(new UInt32(3)));
                p.put("TextDirection", new Variant<>("ltr"));
                p.put("Status", new Variant<>("normal"));
                p.put("IconThemePath", new Variant<>(List.of(), "as"));
            }
            return p;
        }
    }

    // ---- images ----

    /** The logo at several sizes as ARGB32 in network byte order, as StatusNotifierItem expects. */
    private static List<StatusNotifierItem.Pixmap> iconPixmaps(boolean streaming) {
        List<StatusNotifierItem.Pixmap> pixmaps = new ArrayList<>();
        for (int size : ICON_SIZES) {
            BufferedImage img = TrayIcons.trayIcon(size, streaming);
            byte[] data = new byte[size * size * 4];
            int i = 0;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int argb = img.getRGB(x, y);
                    data[i++] = (byte) (argb >>> 24);
                    data[i++] = (byte) (argb >>> 16);
                    data[i++] = (byte) (argb >>> 8);
                    data[i++] = (byte) argb;
                }
            }
            pixmaps.add(new StatusNotifierItem.Pixmap(size, size, data));
        }
        return pixmaps;
    }

    /** The logo as RGBA for the notification {@code image-data} hint. */
    private static Notifications.ImageData notificationImage() {
        int size = 64;
        BufferedImage img = Logo.image(size);
        byte[] rgba = new byte[size * size * 4];
        int i = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int argb = img.getRGB(x, y);
                rgba[i++] = (byte) (argb >>> 16);
                rgba[i++] = (byte) (argb >>> 8);
                rgba[i++] = (byte) argb;
                rgba[i++] = (byte) (argb >>> 24);
            }
        }
        return new Notifications.ImageData(size, size, rgba);
    }
}
