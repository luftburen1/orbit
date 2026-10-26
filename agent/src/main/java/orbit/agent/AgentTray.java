package orbit.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import orbit.tray.TrayBackend;
import orbit.tray.TrayMenu.Entry;
import orbit.tray.TrayMenu.Item;
import orbit.tray.TrayMenu.Separator;

/**
 * The agent's tray icon on a desktop: what the agent is doing, Stop stream or Show idle screen, "Don't allow streams",
 * "Show idle screen at startup" and Quit, plus notifications for the moments that matter. Methods may be called from
 * any thread.
 */
final class AgentTray {

    private static final Logger LOG = Logger.getLogger(AgentTray.class.getName());

    /** What the menu does; called on the event thread, so implementations hand work to the agent's worker. */
    interface Actions {
        void stopStream();

        void setStreamsAllowed(boolean allowed);

        void showIdleScreen();

        void setIdleScreenAtStartup(boolean show);

        void quit();
    }

    /** What the icon shows; equal values aren't sent again. */
    record State(String status, boolean streaming, boolean streamsAllowed, boolean idleScreenAtStartup) {
    }

    private final TrayBackend tray;
    private final Actions actions;
    private final AtomicReference<State> shown = new AtomicReference<>();

    private AgentTray(TrayBackend tray, Actions actions) {
        this.tray = tray;
        this.actions = actions;
    }

    /** The tray icon, if this desktop has a tray; clicking it opens the menu. */
    static Optional<AgentTray> install(Actions actions) {
        AtomicReference<Optional<TrayBackend>> installed = new AtomicReference<>(Optional.empty());
        try {
            SwingUtilities.invokeAndWait(() -> installed.set(TrayBackend.install(
                    new TrayBackend.Options("orbit-agent", "Orbit agent", "Orbit agent", null, null))));
        } catch (Exception e) {
            LOG.warning("cannot add the tray icon: " + e.getMessage());
        }
        if (installed.get().isEmpty()) {
            LOG.info("no system tray on this desktop (on GNOME it needs the AppIndicator extension); running"
                    + " without one");
        }
        return installed.get().map(t -> new AgentTray(t, actions));
    }

    void show(State state) {
        if (Objects.equals(shown.getAndSet(state), state)) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            tray.setToolTip(state.status());
            tray.setStreaming(state.streaming());
            tray.setMenu(menu(state, actions));
        });
    }

    void notify(String title, String message) {
        tray.notify(title, message);
    }

    /** Asks whether to stop the stream, saying what it loses; Cancel is the default. Runs {@code onStop} on OK. */
    void askToStop(String warning, Runnable onStop) {
        SwingUtilities.invokeLater(() -> {
            String[] options = {"OK", "Cancel"};
            int answer = JOptionPane.showOptionDialog(null, warning, "Stop the stream?", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE, null, options, options[1]);
            if (answer == 0) {
                onStop.run();
            }
        });
    }

    /** The status; Stop stream while streaming, else Show idle screen; the streams toggle; the idle screen setting. */
    static List<Entry> menu(State state, Actions actions) {
        List<Entry> menu = new ArrayList<>();
        menu.add(Item.label("status", state.status()));
        menu.add(new Separator());
        if (state.streaming()) {
            menu.add(Item.action("stop", "Stop stream", "media-playback-stop", true, actions::stopStream));
        } else {
            menu.add(Item.action("idle", "Show idle screen", "view-fullscreen", true, actions::showIdleScreen));
        }
        menu.add(state.streamsAllowed()
                ? Item.action("allow", "Don't allow streams", null, true, () -> actions.setStreamsAllowed(false))
                : Item.action("allow", "Allow streams again", null, true, () -> actions.setStreamsAllowed(true)));
        menu.add(new Separator());
        menu.add(Item.toggle("idle-at-startup", "Show idle screen at startup", state.idleScreenAtStartup(),
                () -> actions.setIdleScreenAtStartup(!state.idleScreenAtStartup())));
        menu.add(new Separator());
        menu.add(Item.action("quit", "Quit", "application-exit", true, actions::quit));
        return menu;
    }
}
