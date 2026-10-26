package orbit.agent;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import orbit.protocol.Message;

/**
 * The TV remote while no stream runs. Play/Pause, OK or an arrow asks the host for its apps and opens them as an
 * {@link AppMenu} on the idle screen, where up and down move, OK or Play asks the host to stream the chosen app, and
 * Back or Stop closes it. An older host takes no app: there Play/Pause and OK ask for this client's last app, and the
 * arrows do nothing. Also holds the short notices that show on the idle screen instead of the status.
 *
 * <p>Runs on the agent's worker thread.
 */
final class IdleRemote {

    private static final Logger LOG = Logger.getLogger(IdleRemote.class.getName());

    /** How long a notice stays on the idle screen. */
    static final Duration NOTICE_TIME = Duration.ofSeconds(10);
    /** Play presses this soon after a stream request are ignored: the host may still be starting it. */
    static final Duration REQUEST_DEBOUNCE = Duration.ofSeconds(5);
    /** The app list closes by itself after this long without a key press. */
    static final Duration MENU_TIMEOUT = Duration.ofSeconds(30);

    /** What the remote needs from the agent; called on the worker thread. */
    interface Agent {
        /** Why the remote can't ask for a stream now, for the idle screen; null if it can. */
        String requestRefusal();

        /** The host sends its apps and takes a stream request's app. */
        boolean hostTakesControl();

        void send(Message.FromAgent message);

        /** Shows the idle screen again, with {@link #notice} and {@link #menu} as they are now. */
        void updateIdleScreen();
    }

    private final Agent agent;
    private final LongSupplier nanoTime;
    /** The app list on the idle screen, or null; closes at {@link #menuUntil}. */
    private AppMenu menu;
    private long menuUntil;
    /** Play asked the host for its apps, and the answer hasn't come yet. */
    private boolean menuRequested;
    /** Shown on the idle screen instead of the status until {@link #noticeUntil}, or null. */
    private String notice;
    private long noticeUntil;
    /** When Play may ask for a stream again. */
    private long nextRequest;

    /** @param nanoTime the clock, as {@link System#nanoTime} */
    IdleRemote(Agent agent, LongSupplier nanoTime) {
        this.agent = agent;
        this.nanoTime = nanoTime;
        this.nextRequest = nanoTime.getAsLong();
    }

    /** A key pressed while no stream runs. */
    void key(RemoteKeys.Key key) {
        if (menu != null) {
            menuKey(key);
            return;
        }
        boolean opensList = switch (key) {
            case PLAY_PAUSE, OK -> true;
            // Without the list, an arrow would start a stream by accident.
            case UP, DOWN, LEFT, RIGHT -> agent.hostTakesControl();
            case STOP, BACK -> false;
        };
        if (!opensList || nanoTime.getAsLong() - nextRequest < 0) {
            return;
        }
        String refusal = agent.requestRefusal();
        if (refusal != null) {
            LOG.info(() -> "TV remote asked for a stream, but " + refusal);
            showNotice(refusal);
        } else if (agent.hostTakesControl()) {
            menuRequested = true;
            agent.send(new Message.AppListRequest());
            showNotice("Loading the apps…");
        } else {
            requestStream(null);
        }
    }

    private void menuKey(RemoteKeys.Key key) {
        switch (key) {
            case UP -> menu = menu.up();
            case DOWN -> menu = menu.down();
            case OK, PLAY_PAUSE -> {
                String app = menu.app();
                menu = null;
                requestStream(app);
                return;
            }
            case STOP, BACK -> menu = null;
            case LEFT, RIGHT -> {
            }
        }
        menuUntil = nanoTime.getAsLong() + MENU_TIMEOUT.toNanos();
        agent.updateIdleScreen();
    }

    /**
     * Opens the app list with the host's answer, unless Play's request has been overtaken (e.g. by a stream).
     *
     * @param streaming a stream runs now
     */
    void appList(Message.AppList list, boolean streaming) {
        if (!menuRequested || streaming) {
            return;
        }
        menuRequested = false;
        if (list.apps().isEmpty()) {
            showNotice("Couldn't load the apps" + (list.error() != null ? ": " + list.error() : ""));
            return;
        }
        notice = null;
        menu = AppMenu.of(list.apps(), list.icons(), list.lastApp());
        menuUntil = nanoTime.getAsLong() + MENU_TIMEOUT.toNanos();
        agent.updateIdleScreen();
    }

    /** The host didn't start the stream the remote asked for; Play may ask again at once. */
    void requestRefused(String reason) {
        nextRequest = nanoTime.getAsLong();
        showNotice("Couldn't start a stream: " + reason);
    }

    /** @param app the app picked in the list, or null for the host to start this client's last one */
    private void requestStream(String app) {
        LOG.info(() -> "TV remote asked for a stream" + (app != null ? " of " + app : "") + "; asking the Orbit host");
        nextRequest = nanoTime.getAsLong() + REQUEST_DEBOUNCE.toNanos();
        agent.send(new Message.StreamRequest(app));
        showNotice(app != null ? "Starting " + app + "…" : "Starting a stream…");
    }

    void showNotice(String text) {
        notice = text;
        noticeUntil = nanoTime.getAsLong() + NOTICE_TIME.toNanos();
        agent.updateIdleScreen();
    }

    /** A new connection to the host: an app list asked of the last one won't come. */
    void reset() {
        menu = null;
        menuRequested = false;
    }

    /**
     * Drops the notice and the menu once their time is up, or when a stream runs; called before each idle screen
     * update, which then reads {@link #notice} and {@link #menu}.
     */
    void expire(boolean streaming) {
        long now = nanoTime.getAsLong();
        if (notice != null && (streaming || now >= noticeUntil)) {
            notice = null;
        }
        if (streaming || menu != null && now >= menuUntil) {
            menu = null;
            menuRequested = false;
        }
    }

    /** What shows instead of the status, or null. */
    String notice() {
        return notice;
    }

    /** The app list on the idle screen, or null. */
    AppMenu menu() {
        return menu;
    }
}
