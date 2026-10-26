package orbit.host;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import orbit.host.ClientActions.Action;
import orbit.protocol.ClientState;
import orbit.tray.TrayBackend;
import orbit.tray.TrayMenu.Entry;
import orbit.tray.TrayMenu.Item;
import orbit.tray.TrayMenu.Separator;

/**
 * Tray icon: clicking it opens the window, its menu has Open Orbit, the clients with their status and actions,
 * and Quit, its tooltip says how many are available, and it notifies when one becomes available.
 */
final class TrayController implements AgentRegistry.Listener {

    private static final Logger LOG = Logger.getLogger(TrayController.class.getName());
    /** How long a fetched Sunshine app list is good for before opening the menu fetches it again. */
    private static final Duration APPS_MAX_AGE = Duration.ofSeconds(30);
    /** The crown on the client that leads the stream (the host monitor's mode follows it); no icon theme has one. */
    static final byte[] LEAD_ICON = Icons.crownPng(32);

    /** What the tray menu does; implemented by the main window. All methods run on the Swing event thread. */
    interface Window {
        void show();

        void quit();

        void approve(ClientView client);

        void pair(ClientView client);

        /** Asks, then removes the client's pairing in Sunshine. */
        void unpair(ClientView client);

        void startStream(ClientView client, String app);

        /** Asks which app to stream, then starts it. */
        void chooseAppAndStart(ClientView client);

        void stopStream(ClientView client);

        void editSettings(ClientView client);

        void remove(ClientView client);

        /** What Orbit is doing to the client right now, e.g. "Stopping…", or null. */
        String activity(String agentId);

        /** Why {@code c} can't start a stream because of Sunshine's stream limit, or null. */
        default String streamLimit(ClientView c) {
            return null;
        }

        /** Whether the host monitor's mode follows this client, which streams. */
        default boolean setsHostMonitor(String agentId) {
            return false;
        }

        /** Runs after the window has refreshed its client list or a client's activity changed. */
        void addChangeListener(Runnable listener);
    }

    private final OrbitHost host;
    private final Window window;
    private final TrayBackend tray;
    /** Last known availability per client, to notify only on transitions. Event thread only. */
    private final Map<String, Boolean> available = new HashMap<>();
    /** Ids of the pairing requests waiting in Sunshine, to notify only of new ones. Event thread only. */
    private final Set<String> requests = new HashSet<>();
    /** Sunshine's app names, empty until fetched. Event thread only. */
    private List<String> apps = List.of();
    private long appsFetchedAt;
    private boolean fetchingApps;

    private TrayController(OrbitHost host, Window window, TrayBackend tray) {
        this.host = host;
        this.window = window;
        this.tray = tray;
    }

    /**
     * Installs the tray icon: the D-Bus StatusNotifierItem where the desktop offers one (KDE, GNOME with the
     * AppIndicator extension), else the AWT tray. Returns false if neither is available.
     */
    static boolean install(OrbitHost host, Window window) {
        TrayController[] controller = new TrayController[1];
        Optional<TrayBackend> tray = TrayBackend.install(new TrayBackend.Options("orbit", AppInfo.NAME, "Orbit",
                window::show, () -> {
                    if (controller[0] != null) {
                        controller[0].refreshApps();
                    }
                }));
        if (tray.isEmpty()) {
            LOG.info("no system tray available; closing the window quits Orbit");
            return false;
        }
        TrayController c = new TrayController(host, window, tray.get());
        controller[0] = c;
        host.registry().clients().forEach(client -> c.available.put(client.agentId(), isAvailable(client)));
        c.updateTooltip();
        c.updateMenu();
        c.refreshApps();
        host.registry().addListener(c);
        window.addChangeListener(c::updateMenu);
        return true;
    }

    @Override
    public void clientChanged(ClientView client) {
        SwingUtilities.invokeLater(() -> {
            boolean now = isAvailable(client);
            Boolean before = available.put(client.agentId(), now);
            if (now && !Boolean.TRUE.equals(before)) {
                tray.notify("Orbit", client.hostname() + " is available to stream to");
            } else if (!client.approved() && before == null) {
                tray.notify("Orbit", client.hostname() + " wants to join; approve it in Orbit");
            }
            updateTooltip();
        });
    }

    /** Tells about a Moonlight client without an agent that waits to pair, while its PIN is on its screen. */
    @Override
    public void pairingsChanged() {
        List<ClientView> waiting = host.registry().pairingRequests();
        SwingUtilities.invokeLater(() -> {
            for (ClientView request : waiting) {
                if (requests.add(request.agentId())) {
                    tray.notify("Orbit", request.hostname() + " wants to pair; enter the PIN it shows in Orbit");
                }
            }
            requests.retainAll(waiting.stream().map(ClientView::agentId).toList());
        });
    }

    @Override
    public void clientRemoved(String agentId) {
        SwingUtilities.invokeLater(() -> {
            available.remove(agentId);
            updateTooltip();
        });
    }

    private void updateTooltip() {
        tray.setStreaming(host.registry().clients().stream().anyMatch(ClientView::streaming));
        long count = host.registry().clients().stream().filter(TrayController::isAvailable).count();
        tray.setToolTip(count + (count == 1 ? " client" : " clients") + " available");
    }

    /** Open Orbit, the clients with their actions, then Quit. */
    private void updateMenu() {
        List<Entry> entries = new ArrayList<>();
        entries.add(Item.action("open", "Open Orbit", null, true, window::show));
        entries.add(new Separator());
        entries.addAll(menu(host.registry().clients(), window::activity, apps, window));
        entries.add(new Separator());
        entries.add(Item.action("quit", "Quit", "application-exit", true, window::quit));
        tray.setMenu(entries);
    }

    /** Fetches Sunshine's apps in the background if the list is old, and updates the menu when they arrive. */
    private void refreshApps() {
        SunshineClient sunshine = host.sunshine();
        if (sunshine == null || fetchingApps
                || System.currentTimeMillis() - appsFetchedAt < APPS_MAX_AGE.toMillis()) {
            return;
        }
        fetchingApps = true;
        CompletableFuture.supplyAsync(() -> {
            try {
                return sunshine.listApps().stream().map(SunshineClient.App::name).toList();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, Background.EXECUTOR).whenComplete((names, err) -> SwingUtilities.invokeLater(() -> {
            fetchingApps = false;
            if (err != null) {
                LOG.log(Level.FINE, "cannot list sunshine apps for the tray menu", err);
                return;
            }
            appsFetchedAt = System.currentTimeMillis();
            if (!names.equals(apps)) {
                apps = names;
                updateMenu();
            }
        }));
    }

    /**
     * The client menu: one submenu per client with the actions it allows.
     *
     * @param apps Sunshine's apps; "Desktop" is offered when empty
     */
    static List<Entry> menu(List<ClientView> clients, Function<String, String> activity, List<String> apps,
            Window window) {
        List<Entry> menu = new ArrayList<>();
        if (clients.isEmpty()) {
            menu.add(Item.label("none", "No clients yet"));
        }
        for (ClientView c : clients) {
            String doing = activity.apply(c.agentId());
            Item item = Item.submenu("client/" + c.agentId(), c.hostname() + " — " + status(c, doing),
                    clientMenu(c, doing != null, apps, window));
            if (window.setsHostMonitor(c.agentId())) {
                // Its own key: dbusmenu has no way to say "no icon-data any more", so the item the crown leaves
                // must be a new one.
                item = new Item(item.key() + "#lead", item.label(), item.enabled(), null, null, item.children())
                        .withIconPng(LEAD_ICON);
            }
            menu.add(item);
        }
        return menu;
    }

    private static List<Entry> clientMenu(ClientView c, boolean busy, List<String> apps, Window window) {
        String key = "client/" + c.agentId() + "/";
        String limit = window.streamLimit(c);
        Set<Action> allowed = ClientActions.enabled(c, busy, limit == null);
        List<Entry> items = new ArrayList<>();
        if (allowed.contains(Action.APPROVE)) {
            items.add(Item.action(key + "approve", "Approve", null, true, () -> window.approve(c)));
        }
        if (allowed.contains(Action.PAIR)) {
            items.add(Item.action(key + "pair", "Pair", null, true, () -> window.pair(c)));
        }
        if (allowed.contains(Action.UNPAIR)) {
            items.add(Item.action(key + "unpair", "Unpair…", null, true, () -> window.unpair(c)));
        }
        if (allowed.contains(Action.START)) {
            List<Entry> appItems = new ArrayList<>();
            for (String app : apps.isEmpty() ? List.of("Desktop") : apps) {
                appItems.add(Item.action(key + "start/" + app, app, null, true, () -> window.startStream(c, app)));
            }
            appItems.add(new Separator());
            appItems.add(Item.action(key + "start-other", "Other app…", null, true,
                    () -> window.chooseAppAndStart(c)));
            items.add(new Item(key + "start", "Start stream", true, null, "media-playback-start", appItems));
        } else if (ClientActions.onlyStreamLimit(c, busy, limit) != null) {
            items.add(Item.label(key + "start-limit", "Start stream (" + limit + ")"));
        }
        if (c.streaming()) {
            items.add(Item.action(key + "stop", "Stop stream", "media-playback-stop", allowed.contains(Action.STOP),
                    () -> window.stopStream(c)));
        }
        if (allowed.contains(Action.SETTINGS)) {
            items.add(Item.action(key + "settings", "Stream settings…", "configure", true,
                    () -> window.editSettings(c)));
        }
        items.add(new Separator());
        items.add(Item.action(key + "remove", "Remove client…", "edit-delete", true, () -> window.remove(c)));
        return items;
    }

    private static String status(ClientView c, String activity) {
        if (activity != null) {
            return activity;
        }
        if (c.streaming() && c.currentApp() != null) {
            return "Streaming " + c.currentApp();
        }
        return c.statusLabel();
    }

    private static boolean isAvailable(ClientView c) {
        return c.online() && c.approved() && c.state() == ClientState.AVAILABLE && !c.disabledInSunshine();
    }
}
