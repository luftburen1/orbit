package orbit.host;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import orbit.protocol.Message;
import orbit.protocol.StreamSettings;

/**
 * Starting and stopping streams and changing their settings, from the window, the tray or the client itself. All
 * methods run on the Swing event thread.
 */
final class StreamActions {

    private static final String STARTING = ClientActivities.STARTING;
    private static final String STOPPING = ClientActivities.STOPPING;
    private static final String RECONNECTING = ClientActivities.RECONNECTING;

    private final OrbitHost host;
    private final AgentRegistry registry;
    private final StreamDirector director;
    private final WindowSupport ui;
    /** Clients whose Stop is checking what it loses or asking about it, so a second click doesn't ask again. */
    private final Set<String> askingToStop = new HashSet<>();

    StreamActions(OrbitHost host, WindowSupport ui) {
        this.host = host;
        this.registry = host.registry();
        this.director = host.director();
        this.ui = ui;
    }

    /** Why {@code c} can't start a stream because of Sunshine's stream limit, or null. */
    String streamLimit(ClientView c) {
        return ClientActions.streamLimit(registry.clients(), c, host.maxStreams());
    }

    /** Asks which app to stream, or whether to join the app another client streams, then starts it. */
    void chooseAppAndStart(ClientView client) {
        ui.focus(client);
        Optional<ClientView> other = registry.streamingOther(client.agentId());
        if (other.isPresent()) {
            // Sunshine runs one app at a time: a second stream can only join it.
            String app = other.get().currentApp();
            int answer = JOptionPane.showConfirmDialog(ui.frame, other.get().hostname() + " is streaming " + app
                    + ". Join that stream on " + client.hostname() + "?", "Start stream",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (answer == JOptionPane.OK_OPTION) {
                startStream(client, app);
            }
            return;
        }
        ui.status("Loading apps from Sunshine…");
        loadApps(host.sunshine(), (apps, error) -> {
            ui.status(error != null ? "Could not list Sunshine apps: " + error : " ");
            JComboBox<String> choice = new JComboBox<>(apps.toArray(String[]::new));
            choice.setEditable(true);
            // As on the client's own app list: the app it streamed last.
            String last = registry.lastApp(client.agentId());
            if (apps.contains(last)) {
                choice.setSelectedItem(last);
            }
            int answer = JOptionPane.showConfirmDialog(ui.frame, Forms.form("App to stream to " + client.hostname(),
                    choice), "Start stream", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            Object app = choice.getEditor().getItem();
            if (answer == JOptionPane.OK_OPTION && app != null && !app.toString().isBlank()) {
                startStream(client, app.toString().strip());
            }
        });
    }

    void startStream(ClientView client, String app) {
        String id = client.agentId();
        if (!ui.begin(id, STARTING)) {
            return;
        }
        reportStart(client, "Starting " + app, director.startAsync(id, app), false, app);
    }

    /**
     * Starts the stream a client asked for, e.g. with its TV remote. Whoever asked is at the client, so a failure
     * goes back to its idle screen rather than into a dialog here.
     */
    void startRequested(ClientView asked, String app) {
        String id = asked.agentId();
        ClientView client = registry.client(id).orElse(asked);
        String refusal = ClientActions.startRefusal(client, ui.activities.get(id) != null, streamLimit(client));
        if (refusal != null) {
            ui.status(client.hostname() + " asked for a stream, but " + refusal + ".");
            registry.refuseStreamRequest(id, refusal);
            return;
        }
        ui.begin(id, STARTING); // can't fail: startRefusal checked that nothing else is going on
        reportStart(client, "Starting " + app + " (asked by the client)", director.startAsync(id, app), true, app);
    }

    /**
     * Stops the stream a client asked to stop, e.g. with its TV remote: like {@link #stopStream}, so the app quits
     * unless others still stream it, but without dialogs, since whoever asked is at the client.
     */
    void stopRequested(ClientView asked) {
        String id = asked.agentId();
        ClientView client = registry.client(id).orElse(asked);
        if (!client.streaming() || !ui.begin(id, STOPPING)) {
            return;
        }
        List<String> others = director.othersStreaming(id);
        ui.status("Stopping the stream on " + client.hostname() + " (asked by the client)…");
        director.stop(id).whenComplete((r, err) -> SwingUtilities.invokeLater(() -> {
            if (err != null || !r.ok()) {
                ui.activities.end(id, STOPPING);
                ui.status("Stopping the stream on " + client.hostname() + " failed: "
                        + (err != null ? WindowSupport.errorMessage(err) : r.error()));
            } else {
                ui.status("Stopped the stream on " + client.hostname() + "."
                        + (others.isEmpty() ? "" : " The app keeps running for " + String.join(" and ", others) + "."));
                ui.clearLater(id, STOPPING);
            }
            ui.refresh();
        }));
    }

    /**
     * Stops a client's stream, after asking if that quits an app, which loses anything unsaved in it. Cancel is the
     * default, as on the client's remote.
     */
    void stopStream(ClientView client) {
        String id = client.agentId();
        if (!askingToStop.add(id)) {
            return;
        }
        new SwingWorker<Optional<String>, Void>() {
            @Override
            protected Optional<String> doInBackground() {
                return host.stopQuits(client);
            }

            @Override
            protected void done() {
                Optional<String> quits;
                try {
                    quits = get();
                } catch (Exception e) {
                    // Can't tell: the Stop was clicked, so it stops.
                    quits = Optional.empty();
                }
                try {
                    if (quits.isPresent() && !confirmStop(client, quits.get())) {
                        return;
                    }
                } finally {
                    askingToStop.remove(id);
                }
                stopConfirmed(client);
            }
        }.execute();
    }

    private boolean confirmStop(ClientView client, String quitsApp) {
        String[] options = {"OK", "Cancel"};
        int answer = JOptionPane.showOptionDialog(ui.frame, Message.StopCheck.warning(quitsApp),
                "Stop the stream on " + client.hostname() + "?", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        return answer == 0;
    }

    /** Stops a client's stream, showing "Stopping…" as its status until it no longer streams. */
    private void stopConfirmed(ClientView client) {
        String id = client.agentId();
        if (!ui.begin(id, STOPPING)) {
            return;
        }
        ui.status("Stopping stream on " + client.hostname() + "…");
        List<String> others = director.othersStreaming(id);
        director.stop(id).whenComplete((r, err) -> SwingUtilities.invokeLater(() -> {
            if (err != null || !r.ok()) {
                ui.activities.end(id, STOPPING);
                ui.refresh();
                if (err != null) {
                    ui.failed("Stopping stream on " + client.hostname(), err);
                } else {
                    ui.status("Stopping stream on " + client.hostname() + " failed.");
                    JOptionPane.showMessageDialog(ui.frame, r.error(), "Stopping stream failed",
                            JOptionPane.ERROR_MESSAGE);
                }
                return;
            }
            ui.status("Stopped the stream on " + client.hostname() + "."
                    + (others.isEmpty() ? "" : " The app keeps running for " + String.join(" and ", others) + "."));
            ui.refresh();
            // The client normally reports it stopped right after answering; don't stay "Stopping…" if it doesn't.
            ui.clearLater(id, STOPPING);
        }));
    }

    void editSettings(ClientView client) {
        ui.focus(client);
        StreamSettings before = client.settings();
        Optional<StreamSettings> chosen = SettingsDialog.show(ui.frame, client, host.sunshine());
        if (chosen.isEmpty()) {
            return;
        }
        StreamSettings updated = chosen.get();
        registry.updateSettings(client.agentId(), updated);
        // The stream may have started or stopped while the dialog was open.
        ClientView now = registry.client(client.agentId()).orElse(client);
        if (updated.equals(before)) {
            ui.status("Settings unchanged for " + client.hostname() + ".");
        } else if (now.streaming() && now.currentApp() != null) {
            restartWithNewSettings(now);
        } else if (now.streaming()) {
            ui.status("Settings saved for " + client.hostname()
                    + "; its stream wasn't started from Orbit, so they apply from the next stream.");
        } else {
            ui.status("Settings saved for " + client.hostname() + "; they apply from the next stream.");
        }
    }

    /** Reconnects a streaming client so new settings (and the matching host monitor mode) take effect now. */
    private void restartWithNewSettings(ClientView client) {
        String app = client.currentApp();
        String id = client.agentId();
        // "Reconnecting…" for the whole reconnect: the client stops streaming partway, and must not look Available.
        if (!ui.begin(id, RECONNECTING)) {
            ui.status("Settings saved for " + client.hostname() + "; they apply from the next stream.");
            return;
        }
        new SwingWorker<StreamDirector.StartResult, String>() {
            @Override
            protected StreamDirector.StartResult doInBackground() throws Exception {
                return director.restart(id, app, this::publish);
            }

            @Override
            protected void process(List<String> progress) {
                ui.status(progress.getLast());
            }

            @Override
            protected void done() {
                StreamDirector.StartResult result;
                try {
                    result = get();
                } catch (Exception e) {
                    ui.activities.end(id, RECONNECTING);
                    ui.refresh();
                    ui.failed("Reconnecting " + client.hostname(), e);
                    return;
                }
                if (result.ok()) {
                    reconnected(id);
                } else {
                    ui.activities.end(id, RECONNECTING);
                }
                ui.refresh();
                showStartResult(client, "Reconnecting with the new settings", result);
            }
        }.execute();
    }

    /** Ends a client's "Reconnecting…" like a start: once it reports it streams again, or after 5 s. */
    void reconnected(String id) {
        if (ui.activities.replace(id, RECONNECTING, STARTING)) {
            ui.clearLater(id, STARTING);
        }
    }

    /**
     * Shows a stream start's progress in the status bar and its outcome, also saying what happened to the host
     * monitor; ends the client's "Starting…" once the start has failed or settled.
     *
     * @param requested the client asked for this stream: a failure goes back to it, not into a dialog
     * @param app       what's started; if Sunshine runs another app Orbit didn't start, asks whether to quit it for
     *                  this one (unless requested: nobody at the host may be there to answer)
     */
    private void reportStart(ClientView client, String what, CompletableFuture<StreamDirector.StartResult> result,
            boolean requested, String app) {
        ui.status(what + " on " + client.hostname() + "…");
        result.whenComplete((r, err) -> SwingUtilities.invokeLater(() -> {
            String id = client.agentId();
            if (err != null || !r.ok()) {
                ui.activities.end(id, STARTING);
            } else {
                // The client normally reports it streams right after answering; don't stay "Starting…" if it doesn't.
                ui.clearLater(id, STARTING);
            }
            ui.refresh();
            if (requested && err == null && r.runningApp() != null) {
                String running = r.runningApp().isEmpty() ? "an app" : r.runningApp();
                ui.status(what + " on " + client.hostname() + " refused: " + r.result().error());
                registry.refuseStreamRequest(id, "Sunshine is running " + running + " that Orbit didn't start");
            } else if (err == null && r.runningApp() != null) {
                replaceRunningApp(client, what, app, r);
            } else if (requested && (err != null || !r.ok())) {
                String error = err == null ? r.result().error() : WindowSupport.errorMessage(err);
                ui.status(what + " on " + client.hostname() + " failed: " + error);
                registry.refuseStreamRequest(id, error);
            } else if (err != null) {
                ui.failed(what + " on " + client.hostname(), err);
            } else {
                showStartResult(client, what, r);
            }
        }));
    }

    /** Asks whether to quit the app Sunshine runs that Orbit didn't start, and starts {@code app} if so. */
    private void replaceRunningApp(ClientView client, String what, String app, StreamDirector.StartResult r) {
        ui.status(what + " on " + client.hostname() + ": Sunshine is running "
                + (r.runningApp().isEmpty() ? "an app" : r.runningApp()) + " that Orbit didn't start.");
        int answer = JOptionPane.showConfirmDialog(ui.frame, r.result().error() + " Start " + app + " anyway?",
                what, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.OK_OPTION && ui.begin(client.agentId(), STARTING)) {
            reportStart(client, what, director.startAsync(client.agentId(), app, true), false, app);
        }
    }

    private void showStartResult(ClientView client, String what, StreamDirector.StartResult r) {
        String note = r.displayNote() != null ? " " + r.displayNote() + "." : "";
        if (!r.ok()) {
            ui.status(what + " on " + client.hostname() + " failed." + note);
            JOptionPane.showMessageDialog(ui.frame, r.result().error(), what + " failed", JOptionPane.ERROR_MESSAGE);
        } else {
            ui.status(what + " on " + client.hostname() + ": done." + note);
        }
    }

    /**
     * Loads Sunshine's app names off the event thread. Falls back to "Desktop" if Sunshine is unavailable, passing the
     * reason as the second argument (null on success).
     */
    static void loadApps(SunshineClient sunshine, BiConsumer<List<String>, String> then) {
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                return sunshine == null ? List.of()
                        : sunshine.listApps().stream().map(SunshineClient.App::name).toList();
            }

            @Override
            protected void done() {
                List<String> apps = List.of();
                String error = null;
                try {
                    apps = get();
                } catch (Exception e) {
                    error = AgentRegistry.rootMessage(e);
                }
                then.accept(apps.isEmpty() ? List.of("Desktop") : apps, error);
            }
        }.execute();
    }
}
