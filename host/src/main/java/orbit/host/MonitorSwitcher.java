package orbit.host;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import orbit.protocol.Message;

/**
 * Switches Sunshine's monitor without leaving clients on a broken stream. Switching restarts Sunshine, so clients
 * that are streaming are disconnected first and started again on the same app once Sunshine is back.
 */
final class MonitorSwitcher {

    private static final Logger LOG = Logger.getLogger(MonitorSwitcher.class.getName());

    static final int START_ATTEMPTS = 4;
    static final Duration START_RETRY_DELAY = Duration.ofSeconds(3);

    /** Changes Sunshine's monitor and returns once the restarted Sunshine answers. */
    interface Selector {
        void selectMonitor(String id) throws IOException, InterruptedException;
    }

    /**
     * @param switchError why Sunshine could not be switched, or null if it was
     * @param reconnected clients streaming again, e.g. "livingroom (Desktop)"
     * @param failed      clients that could not be reconnected, with the reason
     * @param displayNote what happened to the host monitor's resolution on reconnect, or null
     */
    record Result(String switchError, List<String> reconnected, List<String> failed, String displayNote) {
    }

    private final AgentRegistry registry;
    private final StreamDirector director;
    private final Duration retryDelay;

    MonitorSwitcher(AgentRegistry registry, StreamDirector director) {
        this(registry, director, START_RETRY_DELAY);
    }

    MonitorSwitcher(AgentRegistry registry, StreamDirector director, Duration retryDelay) {
        this.registry = registry;
        this.director = director;
        this.retryDelay = retryDelay;
    }

    /** Clients whose stream a switch would interrupt. */
    List<ClientView> streamingClients() {
        return director.streamingClients();
    }

    /**
     * Disconnects streaming clients, switches the monitor, then reconnects them. Reconnecting is attempted even if
     * the switch fails, since Sunshine may still be running on the old monitor.
     */
    Result switchTo(String id, Selector sunshine, Consumer<String> progress) throws InterruptedException {
        // Keep the old monitor's resolution until the clients are back; reconnecting moves it to the new monitor.
        director.hold();
        try {
            return switchHeld(id, sunshine, progress);
        } finally {
            director.release();
        }
    }

    private Result switchHeld(String id, Selector sunshine, Consumer<String> progress) throws InterruptedException {
        List<ClientView> streaming = streamingClients();
        disconnect(streaming, progress);
        String switchError = null;
        // Sunshine only sees the monitors that are on when it starts; they go off again once the switch is done.
        director.wakeMonitors();
        progress.accept("Restarting Sunshine on " + (id.isBlank() ? "its default monitor" : id) + "…");
        try {
            sunshine.selectMonitor(id);
        } catch (IOException e) {
            switchError = AgentRegistry.rootMessage(e);
            LOG.warning("switching Sunshine to " + id + " failed: " + switchError);
        }
        Result result = reconnect(streaming, switchError, progress);
        if (!result.failed().isEmpty()) {
            LOG.warning(() -> "not reconnected after monitor switch: " + result.failed());
        }
        return result;
    }

    private void disconnect(List<ClientView> streaming, Consumer<String> progress) throws InterruptedException {
        if (streaming.isEmpty()) {
            return;
        }
        progress.accept("Disconnecting " + names(streaming) + "…");
        Map<ClientView, CompletableFuture<Message.CommandResult>> pending = new LinkedHashMap<>();
        streaming.forEach(c -> pending.put(c, registry.disconnectStream(c.agentId())));
        for (var entry : pending.entrySet()) {
            // A client that fails to disconnect loses its stream anyway when Sunshine restarts.
            String error = await(entry.getValue());
            if (error != null) {
                LOG.warning(() -> "disconnecting " + entry.getKey().hostname() + " failed: " + error);
            }
        }
    }

    private Result reconnect(List<ClientView> streaming, String switchError, Consumer<String> progress)
            throws InterruptedException {
        List<String> reconnected = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        String displayNote = null;
        for (ClientView client : streaming) {
            String app = client.currentApp();
            if (app == null) {
                failed.add(client.hostname() + ": its stream was not started by Orbit, so the app is unknown");
                continue;
            }
            String error = null;
            for (int attempt = 1; attempt <= START_ATTEMPTS; attempt++) {
                progress.accept("Reconnecting " + client.hostname() + " to " + app
                        + (attempt > 1 ? " (attempt " + attempt + " of " + START_ATTEMPTS + ")" : "") + "…");
                StreamDirector.StartResult started = director.reconnect(client.agentId(), app);
                error = started.ok() ? null : started.result().error();
                if (started.displayNote() != null) {
                    displayNote = started.displayNote();
                }
                if (error == null || attempt == START_ATTEMPTS) {
                    break;
                }
                // Sunshine's web API can answer before its streaming side is ready.
                Thread.sleep(retryDelay.toMillis());
            }
            if (error == null) {
                reconnected.add(client.hostname() + " (" + app + ")");
            } else {
                failed.add(client.hostname() + ": " + error);
            }
        }
        return new Result(switchError, reconnected, failed, displayNote);
    }

    /** Waits for a command; returns null on success, else why it failed. */
    private static String await(CompletableFuture<Message.CommandResult> command) throws InterruptedException {
        Message.CommandResult r = StreamDirector.await(command);
        return r.ok() ? null : r.error();
    }

    private static String names(List<ClientView> clients) {
        return clients.stream().map(ClientView::hostname).collect(Collectors.joining(", "));
    }
}
