package orbit.host;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import orbit.protocol.Message;

/**
 * Starts streams so the host monitor matches them: before a stream starts, the monitor Sunshine captures is set to
 * the client's resolution, and once nothing streams any more its original mode is restored.
 *
 * <p>With several clients, the first stream wins: the client that set the mode keeps it, and clients that join later
 * are scaled from it. When that client stops, the mode follows the client that has streamed longest of those left.
 *
 * <p>Deliberate reconnects (new settings, monitor switch) {@link #hold} the restore so the monitor doesn't flip back
 * and forth while the client is briefly disconnected.
 */
final class StreamDirector implements AgentRegistry.Listener, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(StreamDirector.class.getName());

    /** How long nothing must stream before the original mode is restored, to ride out quick reconnects. */
    static final Duration RESTORE_DELAY = Duration.ofSeconds(3);
    /** How long a monitor gets after being turned on before the stream starts, for the desktop to set it up. */
    static final Duration MONITOR_SETTLE = Duration.ofSeconds(2);

    /** Checks that Sunshine is running, so a client isn't sent to stream from nothing. */
    interface SunshineCheck {
        /** @throws IOException saying why Sunshine can't be reached */
        void ensureRunning() throws IOException;
    }

    /** How many clients Sunshine streams to at once. */
    interface StreamLimit {
        int maxStreams() throws IOException, InterruptedException;
    }

    /** The id of the app Sunshine runs, 0 if none; see {@link SunshineClient#currentGame}. */
    interface RunningApp {
        long currentGame() throws IOException, InterruptedException;
    }

    /** Rereads Sunshine's pairings into the registry, so a start sees whether the client was just disabled. */
    interface PairingRefresh {
        void refresh() throws IOException, InterruptedException;
    }

    /** Which monitor Sunshine captures, as a connector name; empty if unknown. */
    interface OutputResolver {
        Optional<String> sunshineOutput() throws IOException, InterruptedException;
    }

    /** Puts a stream's windows on Sunshine's monitor; {@link #NONE} where the desktop can't. */
    interface WindowPlacer {
        WindowPlacer NONE = new WindowPlacer() {
            @Override
            public void streamStarting(String output, String app) {
            }

            @Override
            public void idle() {
            }
        };

        /** A stream of {@code app} is about to start, from Sunshine's monitor {@code output}. */
        void streamStarting(String output, String app);

        /** Nothing streams any more. */
        void idle();
    }

    /** Unlocks the session for a stream; {@link #NONE} where the desktop can't. */
    interface ScreenUnlocker {
        ScreenUnlocker NONE = () -> { };

        /** A stream is about to start: unlocks a locked session if that's set. A failure doesn't stop the stream. */
        void streamStarting() throws InterruptedException;
    }

    /**
     * @param result      the client's answer to the start command
     * @param displayNote what happened to the host monitor, or null if it was left alone
     * @param runningApp set when the start was refused because Sunshine runs another app that Orbit didn't start,
     *                   which it would quit: its name, or empty if unknown. Starting with {@code replace} goes ahead.
     */
    record StartResult(Message.CommandResult result, String displayNote, String runningApp) {
        StartResult(Message.CommandResult result, String displayNote) {
            this(result, displayNote, null);
        }

        boolean ok() {
            return result.ok();
        }
    }

    private final AgentRegistry registry;
    private final HostResolution resolution;
    private final OutputResolver outputs;
    private final SunshineCheck sunshineCheck;
    /** Null without display control, like {@link #resolution}. */
    private final MonitorPower power;
    private volatile boolean turnOffWhenIdle;
    /** Whether a stream sets Sunshine's monitor to the client's resolution; it's only turned on otherwise. */
    private volatile boolean matchResolution = true;
    /** True while an idle check is queued, so a burst of client updates queues just one. */
    private final AtomicBoolean checkQueued = new AtomicBoolean();
    private final Duration restoreDelay;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("orbit-stream-director").factory());
    private int holds;
    private volatile StreamLimit streamLimit = () -> Integer.MAX_VALUE;
    private volatile PairingRefresh pairingRefresh = () -> { };
    private volatile RunningApp runningApp = () -> 0;
    private final AppIds appIds = new AppIds();
    private volatile WindowPlacer windows = WindowPlacer.NONE;
    private volatile ScreenUnlocker unlocker = ScreenUnlocker.NONE;
    /** The client the monitor's mode follows, or null; guarded by this. */
    private String modeOwner;
    /** Streaming clients in the order they started, as seen by this director; guarded by this. */
    private final LinkedHashSet<String> streamOrder = new LinkedHashSet<>();
    /** The app each client is being started with, until it answers; see {@link #claimApp}. */
    private final Map<String, String> startingApps = new HashMap<>();
    private final List<Runnable> ownerListeners = new CopyOnWriteArrayList<>();

    /** @param resolution null when the host monitor can't be changed (no supported display tool) */
    StreamDirector(AgentRegistry registry, HostResolution resolution, OutputResolver outputs, Duration restoreDelay) {
        this(registry, resolution, outputs, () -> { }, restoreDelay);
    }

    StreamDirector(AgentRegistry registry, HostResolution resolution, OutputResolver outputs,
            SunshineCheck sunshineCheck, Duration restoreDelay) {
        this(registry, resolution, outputs, sunshineCheck, restoreDelay, MONITOR_SETTLE);
    }

    /** @param monitorSettle how long a monitor that was turned on gets before the stream starts */
    StreamDirector(AgentRegistry registry, HostResolution resolution, OutputResolver outputs,
            SunshineCheck sunshineCheck, Duration restoreDelay, Duration monitorSettle) {
        this.power = resolution != null ? new MonitorPower(resolution.display(), monitorSettle) : null;
        this.registry = registry;
        this.resolution = resolution;
        this.outputs = outputs;
        this.sunshineCheck = sunshineCheck;
        this.restoreDelay = restoreDelay;
        registry.addListener(this);
    }

    /** Resolves Sunshine's {@code output_name} (a connector name, an index, or empty for its default) to a name. */
    static Optional<String> sunshineOutput(SunshineClient sunshine) throws IOException, InterruptedException {
        if (sunshine == null) {
            return Optional.empty();
        }
        String configured = sunshine.currentMonitor();
        if (!configured.isBlank() && !configured.chars().allMatch(Character::isDigit)) {
            return Optional.of(configured);
        }
        int index = configured.isBlank() ? 0 : Integer.parseInt(configured);
        return sunshine.listMonitors().stream().filter(m -> m.index() == index).map(SunshineClient.Monitor::id)
                .findFirst();
    }

    // ---- starting ----

    CompletableFuture<StartResult> startAsync(String agentId, String app) {
        return startAsync(agentId, app, false);
    }

    /** @param replace quit an app Sunshine runs that Orbit didn't start, instead of refusing; see {@link #start} */
    CompletableFuture<StartResult> startAsync(String agentId, String app, boolean replace) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return start(agentId, app, true, replace);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }, Background.EXECUTOR);
    }

    /**
     * Checks that Sunshine runs, sets the host monitor for the client's settings, then starts the stream. Blocks until
     * the client answers. If the stream doesn't start, the client quits the app it may have launched, unless another
     * client streams it or is starting it, or it ran before.
     *
     * <p>Refuses (with {@link StartResult#runningApp}) when Sunshine runs an app that no Orbit client streams or is
     * starting, unless it's {@code app}: another Moonlight client may stream it, and Moonlight would quit it to start
     * {@code app}.
     */
    StartResult start(String agentId, String app) throws InterruptedException {
        return start(agentId, app, true, false);
    }

    /**
     * Starts the stream again for a client that streamed {@code app} a moment ago, e.g. with new settings or from
     * another monitor. The app is running already, so a failure leaves it running: the user's game may be in it.
     */
    StartResult reconnect(String agentId, String app) throws InterruptedException {
        return start(agentId, app, false, false);
    }

    private StartResult start(String agentId, String app, boolean fresh, boolean replace) throws InterruptedException {
        Optional<StartResult> notRunning = sunshineNotRunning();
        if (notRunning.isPresent()) {
            return notRunning.get();
        }
        Optional<StartResult> disabled = disabledInSunshine(agentId);
        if (disabled.isPresent()) {
            return disabled.get();
        }
        Optional<StartResult> full = streamLimitReached(agentId);
        if (full.isPresent()) {
            return full.get();
        }
        long running = currentGame();
        if (fresh && !replace) {
            Optional<StartResult> foreign = foreignApp(agentId, app, running);
            if (foreign.isPresent()) {
                return foreign.get();
            }
        }
        Optional<StartResult> otherApp = claimApp(agentId, app);
        if (otherApp.isPresent()) {
            return otherApp.get();
        }
        hold();
        try {
            // First, or the app's windows open behind the lock screen, and the stream shows it.
            unlocker.streamStarting();
            String note = prepareDisplay(agentId);
            placeWindows(app);
            // An app that ran before isn't this start's to quit, unless it was to be replaced anyway.
            boolean quitOnFailure = fresh && aloneWithApp(agentId) && (running <= 0 || replace);
            Message.CommandResult result = await(registry.startStream(agentId, app, quitOnFailure));
            if (result.ok()) {
                appIds.learn(currentGame(), app);
            }
            if (!result.ok() && streamingClients().isEmpty()) {
                // No stream will end to stop the placement (and its "Starting…" notice) begun for this one.
                windows.idle();
            }
            return new StartResult(result, note);
        } finally {
            synchronized (this) {
                startingApps.remove(agentId);
            }
            release();
        }
    }

    void setRunningApp(RunningApp runningApp) {
        this.runningApp = runningApp;
    }

    /** The app Sunshine runs, 0 if none, or -1 if Sunshine doesn't say. */
    private long currentGame() throws InterruptedException {
        try {
            return runningApp.currentGame();
        } catch (IOException e) {
            LOG.log(Level.FINE, "cannot read which app Sunshine runs", e);
            return -1;
        }
    }

    /**
     * Keeps the learned app ids in {@code file}, and reads what earlier runs learned, so an app Orbit didn't start
     * this run can still be joined.
     */
    void rememberAppIdsIn(Path file) {
        appIds.rememberIn(file);
    }

    /**
     * Refuses when Sunshine runs an app no Orbit client streams or is starting (they'd know it: see
     * {@link #claimApp}) and it isn't {@code app}, or isn't known to be. Someone may stream it from a Moonlight
     * without an agent, or have left it running.
     */
    private Optional<StartResult> foreignApp(String agentId, String app, long running) {
        if (running <= 0 || !othersStreaming(agentId).isEmpty()) {
            return Optional.empty();
        }
        synchronized (this) {
            if (startingApps.keySet().stream().anyMatch(id -> !id.equals(agentId))) {
                return Optional.empty();
            }
        }
        String name = appIds.name(running);
        if (app.equals(name)) {
            return Optional.empty(); // joining it
        }
        String what = name != null ? name : "an app";
        LOG.info(() -> "not starting " + app + " on " + hostname(agentId) + ": Sunshine runs " + what
                + " that Orbit didn't start");
        return Optional.of(new StartResult(Message.CommandResult.failure(null, "Sunshine is running " + what
                + " that Orbit didn't start; another Moonlight client may be streaming it. Starting " + app
                + " would quit it."), null, name != null ? name : ""));
    }

    /** No other client streams or is starting a stream: quitting the app would end nobody else's. */
    private boolean aloneWithApp(String agentId) {
        if (!othersStreaming(agentId).isEmpty()) {
            return false;
        }
        synchronized (this) {
            return startingApps.keySet().stream().allMatch(agentId::equals);
        }
    }

    /**
     * Sunshine runs one app at a time, and a client asking for another would have Moonlight quit it under the
     * clients streaming it, so while one streams (or is starting) another client can only join the same app. Claims
     * {@code app} for {@code agentId} until its start ends, or refuses.
     */
    private Optional<StartResult> claimApp(String agentId, String app) {
        // Read before taking this lock: the registry calls clientChanged with its own lock held.
        Optional<ClientView> streaming = registry.streamingOther(agentId);
        String other;
        String running;
        synchronized (this) {
            Optional<Map.Entry<String, String>> starting = startingApps.entrySet().stream()
                    .filter(e -> !e.getKey().equals(agentId)).findFirst();
            if (streaming.isPresent()) {
                other = streaming.get().agentId();
                running = streaming.get().currentApp();
            } else if (starting.isPresent()) {
                other = starting.get().getKey();
                running = starting.get().getValue();
            } else {
                other = null;
                running = app;
            }
            if (running.equals(app)) {
                startingApps.put(agentId, app);
                return Optional.empty();
            }
        }
        String name = hostname(other);
        LOG.info(() -> "not starting " + app + " on " + hostname(agentId) + ": " + name + " streams " + running);
        return Optional.of(new StartResult(Message.CommandResult.failure(null, name + " is streaming " + running
                + ", and a second stream can only join it. Stop that stream first to start " + app + "."), null));
    }

    /**
     * Reconnects a streaming client so new settings take effect: disconnects it (leaving the app running on the
     * host), sets the monitor for the new settings and starts the same app again.
     */
    StartResult restart(String agentId, String app, Consumer<String> progress) throws InterruptedException {
        // Checked before disconnecting, so a working stream isn't dropped for a reconnect that can't happen.
        Optional<StartResult> notRunning = sunshineNotRunning();
        if (notRunning.isPresent()) {
            return notRunning.get();
        }
        hold();
        try {
            String name = hostname(agentId);
            progress.accept("Disconnecting " + name + "…");
            Message.CommandResult stopped = await(registry.disconnectStream(agentId));
            if (!stopped.ok()) {
                LOG.warning(() -> "disconnecting " + name + " failed: " + stopped.error());
            }
            progress.accept("Reconnecting " + name + " to " + app + " with the new settings…");
            return reconnect(agentId, app);
        } finally {
            release();
        }
    }

    void setWindowPlacer(WindowPlacer placer) {
        this.windows = placer;
    }

    /** Has the app's windows put on Sunshine's monitor; a failure only leaves them where the desktop puts them. */
    private void placeWindows(String app) throws InterruptedException {
        if (windows == WindowPlacer.NONE) {
            return;
        }
        try {
            Optional<String> output = outputs.sunshineOutput();
            if (output.isPresent()) {
                windows.streamStarting(output.get(), app);
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "cannot tell which monitor Sunshine streams; not placing windows", e);
        }
    }

    void setScreenUnlocker(ScreenUnlocker unlocker) {
        this.unlocker = unlocker;
    }

    /** Asked before each start; unlimited until set. */
    void setStreamLimit(StreamLimit limit) {
        this.streamLimit = limit;
    }

    void setPairingRefresh(PairingRefresh refresh) {
        this.pairingRefresh = refresh;
    }

    /**
     * Sunshine refuses a client disabled in its web UI, and Moonlight then says it isn't paired. Sunshine's list is
     * read only every 30 s, so it's read again first; if that fails, the last one counts.
     */
    private Optional<StartResult> disabledInSunshine(String agentId) throws InterruptedException {
        try {
            pairingRefresh.refresh();
        } catch (IOException e) {
            LOG.log(Level.FINE, "cannot read Sunshine's paired clients", e);
        }
        if (!registry.client(agentId).map(ClientView::disabledInSunshine).orElse(false)) {
            return Optional.empty();
        }
        LOG.info(() -> "not starting a stream to " + hostname(agentId) + ": it's disabled in Sunshine");
        return Optional.of(new StartResult(Message.CommandResult.failure(null, hostname(agentId)
                + " is disabled in Sunshine. Enable it in Sunshine's web UI (Clients) to stream to it."), null));
    }

    /**
     * Sunshine would take the new stream and drop another client's, so it's refused while Sunshine streams to as
     * many clients as it allows. If the limit can't be read, the stream is allowed.
     */
    private Optional<StartResult> streamLimitReached(String agentId) throws InterruptedException {
        int max;
        try {
            max = streamLimit.maxStreams();
        } catch (IOException e) {
            LOG.log(Level.FINE, "cannot read how many streams Sunshine allows", e);
            return Optional.empty();
        }
        Optional<ClientView> client = registry.client(agentId);
        String limit = client.map(c -> ClientActions.streamLimit(registry.clients(), c, max)).orElse(null);
        if (limit == null) {
            return Optional.empty();
        }
        List<String> others = othersStreaming(agentId);
        String message = limit + ", and " + String.join(" and ", others) + (others.size() == 1 ? " is" : " are")
                + " streaming. Stop a stream first, or raise \"channels\" in Sunshine's settings.";
        LOG.info(() -> "not starting a stream to " + hostname(agentId) + ": " + limit.toLowerCase());
        return Optional.of(new StartResult(Message.CommandResult.failure(null, message), null));
    }

    /**
     * Moonlight would otherwise spend 30 s looking for the host and then sit on an error screen that looks like a
     * running stream.
     */
    private Optional<StartResult> sunshineNotRunning() {
        try {
            sunshineCheck.ensureRunning();
            return Optional.empty();
        } catch (IOException e) {
            LOG.info(() -> "not starting a stream: " + e.getMessage());
            return Optional.of(new StartResult(Message.CommandResult.failure(null, e.getMessage()), null));
        }
    }

    /**
     * Turns Sunshine's monitor on if it's off and sets it for the client's settings, unless another client that
     * streams already owns the mode. Failures don't stop the stream; they come back as the note.
     */
    private String prepareDisplay(String agentId) throws InterruptedException {
        if (resolution == null) {
            return null;
        }
        try {
            Optional<String> output = outputs.sunshineOutput();
            if (output.isEmpty()) {
                return "Host monitor unchanged: Sunshine's monitor is unknown (is the Sunshine login set up?)";
            }
            ClientView client = registry.client(agentId).orElseThrow(() -> new IOException("unknown client"));
            String owner = modeOwner();
            if (owner != null && !owner.equals(agentId) && isStreaming(owner)) {
                return "Host monitor kept at " + currentMode(output.get()) + " for " + hostname(owner)
                        + ", which leads";
            }
            boolean turnedOn = power.ensureOn(output.get());
            if (!matchResolution) {
                return turnedOn ? output.get() + " turned on" : null;
            }
            String note = resolution.apply(output.get(), client.settings());
            setModeOwner(agentId);
            return turnedOn ? output.get() + " turned on; " + note.substring(output.get().length() + 1) : note;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "cannot change the host monitor", e);
            return "Host monitor unchanged: " + AgentRegistry.rootMessage(e);
        }
    }

    private String currentMode(String output) throws IOException, InterruptedException {
        return output + " " + resolution.display().outputs().stream().filter(o -> o.name().equals(output))
                .findFirst().flatMap(DisplayControl.Output::currentMode).map(Object::toString).orElse("as it is");
    }

    // ---- stopping ----

    /**
     * Stops a client's stream. Quitting the app on the host would end every other client's stream of it too, so
     * while others stream, the client only disconnects and the app keeps running for them; the last one to stop
     * quits it.
     */
    CompletableFuture<Message.CommandResult> stop(String agentId) {
        List<String> others = othersStreaming(agentId);
        if (others.isEmpty()) {
            if (windows == WindowPlacer.NONE || worker.isShutdown()) {
                return registry.stopStream(agentId);
            }
            // Placing stops first: Sunshine quits the app and runs its undo command, which brings back windows that
            // aren't the stream's (Steam's main window after Big Picture), before the client reports the stream ended.
            return CompletableFuture.runAsync(windows::idle, worker)
                    .thenCompose(placingStopped -> registry.stopStream(agentId));
        }
        LOG.info(() -> "disconnecting " + hostname(agentId) + " without quitting the app: "
                + String.join(", ", others) + " still streaming");
        return registry.disconnectStream(agentId);
    }

    /**
     * Disconnects streaming clients whose name was in Sunshine's paired list {@code before} and isn't {@code now}:
     * someone unpaired them in Sunshine, which doesn't end a running stream itself. Only a name that disappears
     * counts, since clients paired before Orbit have names other than their hostname. Disconnect rather than stop:
     * Sunshine refuses a quit from a client it no longer knows. Returns the names disconnected.
     */
    List<String> disconnectUnpaired(Collection<String> before, Collection<String> now) {
        BiPredicate<Collection<String>, String> has =
                (names, name) -> names.stream().anyMatch(n -> n.equalsIgnoreCase(name));
        List<ClientView> unpaired = streamingClients().stream()
                .filter(c -> has.test(before, c.hostname()) && !has.test(now, c.hostname()))
                .toList();
        for (ClientView c : unpaired) {
            LOG.info(() -> c.hostname() + " was unpaired in Sunshine; ending its stream");
            registry.disconnectStream(c.agentId()).whenComplete((r, e) -> {
                if (e != null || !r.ok()) {
                    LOG.warning(() -> "cannot end the stream of unpaired " + c.hostname() + ": "
                            + (e != null ? AgentRegistry.rootMessage(e) : r.error()));
                }
            });
        }
        return unpaired.stream().map(ClientView::hostname).toList();
    }

    // ---- who streams, and which client the mode follows ----

    /** The client whose settings the host monitor's mode follows, or null if none. */
    synchronized String modeOwner() {
        return modeOwner;
    }

    /** Runs (on any thread) when {@link #modeOwner} changes. */
    void addOwnerListener(Runnable listener) {
        ownerListeners.add(listener);
    }

    private void setModeOwner(String agentId) {
        synchronized (this) {
            if (Objects.equals(modeOwner, agentId)) {
                return;
            }
            modeOwner = agentId;
        }
        ownerListeners.forEach(Runnable::run);
    }

    /**
     * Streaming clients: the mode's owner first, then those that have streamed longest. A monitor switch reconnects
     * them in this order, so the owner gets the mode back.
     */
    List<ClientView> streamingClients() {
        List<ClientView> streaming = registry.clients().stream()
                .filter(c -> c.streaming() && c.approved())
                .toList();
        List<String> order = new ArrayList<>();
        synchronized (this) {
            if (modeOwner != null) {
                order.add(modeOwner);
            }
            order.addAll(streamOrder);
        }
        return streaming.stream().sorted(Comparator.comparingInt(c -> {
            int i = order.indexOf(c.agentId());
            return i < 0 ? Integer.MAX_VALUE : i;
        })).toList();
    }

    /** The other clients that stream, by name; a stop leaves the app running for them. */
    List<String> othersStreaming(String agentId) {
        return streamingClients().stream().filter(c -> !c.agentId().equals(agentId)).map(ClientView::hostname)
                .toList();
    }

    private boolean isStreaming(String agentId) {
        return registry.client(agentId).map(ClientView::streaming).orElse(false);
    }

    /** @return true when this ended the last stream */
    private synchronized boolean trackOrder(ClientView c) {
        if (c.streaming()) {
            streamOrder.add(c.agentId());
            return false;
        }
        return streamOrder.remove(c.agentId()) && streamOrder.isEmpty();
    }

    // ---- monitors: turning off while idle, and what the Monitor menu shows ----

    /**
     * Whether Sunshine's monitor is turned off while nothing streams. Applied after {@code after}, which at startup
     * gives clients that are already streaming time to reconnect and say so.
     */
    void setTurnOffWhenIdle(boolean on, Duration after) {
        turnOffWhenIdle = on;
        if (power != null && !worker.isShutdown()) {
            worker.schedule(this::restoreIfIdle, after.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    boolean turnOffWhenIdle() {
        return turnOffWhenIdle;
    }

    /**
     * Whether streams set Sunshine's monitor to the client's resolution. Turned off, the next stream leaves the mode as
     * it is; one set already is restored when nothing streams any more.
     */
    void setMatchResolution(boolean on) {
        matchResolution = on;
    }

    /** Keeps the monitors Orbit turned off in {@code file}, so they're turned back on even after a crash. */
    void rememberTurnedOffMonitorsIn(Path file) {
        if (power != null) {
            power.rememberIn(file);
        }
    }

    /** Why Sunshine's monitor can't be turned off while idle (e.g. it's the primary), or empty if it can. */
    Optional<String> turnOffRefusal() throws IOException, InterruptedException {
        if (power == null) {
            return Optional.of("Orbit can't turn monitors on and off on this desktop");
        }
        Optional<String> output = outputs.sunshineOutput();
        if (output.isEmpty()) {
            return Optional.of("Sunshine's monitor is unknown");
        }
        return power.refusal(output.get());
    }

    /** The desktop's primary monitor by connector name, which is never turned off; empty without display control. */
    Optional<String> primaryMonitor() throws IOException, InterruptedException {
        if (resolution == null) {
            return Optional.empty();
        }
        return resolution.display().outputs().stream().filter(DisplayControl.Output::primary)
                .map(DisplayControl.Output::name).findFirst();
    }

    /** The connected monitors by connector name, and whether each is on; empty without display control. */
    Map<String, Boolean> connectedMonitors() throws IOException, InterruptedException {
        if (resolution == null) {
            return Map.of();
        }
        return resolution.display().outputs().stream()
                .collect(Collectors.toMap(DisplayControl.Output::name, DisplayControl.Output::enabled));
    }

    /**
     * Turns the monitors Orbit turned off back on, before Sunshine restarts: Sunshine only lists and captures the
     * monitors that are on when it starts. The idle check turns them off again afterwards.
     */
    void wakeMonitors() {
        if (power != null) {
            power.restoreAll();
        }
    }

    /** Sunshine's monitor, if known; for labelling the setting. */
    Optional<String> sunshineMonitor() throws IOException, InterruptedException {
        return outputs.sunshineOutput();
    }

    // ---- restoring ----

    /** Keeps the monitor as it is until a matching {@link #release}, e.g. during a deliberate reconnect. */
    synchronized void hold() {
        holds++;
    }

    void release() {
        synchronized (this) {
            holds--;
        }
        scheduleRestoreCheck();
    }

    @Override
    public void clientChanged(ClientView client) {
        if (trackOrder(client)) {
            lastStreamEnded();
        }
        scheduleRestoreCheck();
    }

    @Override
    public void clientRemoved(String agentId) {
        boolean ended;
        synchronized (this) {
            ended = streamOrder.remove(agentId) && streamOrder.isEmpty();
        }
        if (ended) {
            lastStreamEnded();
        }
        scheduleRestoreCheck();
    }

    /**
     * Stops placing windows at once, not after the restore delay: the app's undo command runs as the stream ends, and
     * windows it brings back (Steam's main window after Big Picture) must stay where they were. A deliberate reconnect
     * holds, and keeps placing for the same app.
     */
    private void lastStreamEnded() {
        synchronized (this) {
            if (holds > 0) {
                return;
            }
        }
        if (windows != WindowPlacer.NONE && !worker.isShutdown() && streamingClients().isEmpty()) {
            worker.execute(windows::idle);
        }
    }

    private void scheduleRestoreCheck() {
        if (resolution == null && windows == WindowPlacer.NONE || worker.isShutdown()
                || !checkQueued.compareAndSet(false, true)) {
            return;
        }
        worker.schedule(() -> {
            checkQueued.set(false);
            restoreIfIdle();
        }, restoreDelay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Once nothing streams: the monitor's original mode back, then the monitor off if that's set. While others still
     * stream after the mode's owner stopped: the mode follows the one that has streamed longest.
     */
    private void restoreIfIdle() {
        synchronized (this) {
            if (holds > 0) {
                return;
            }
        }
        List<ClientView> streaming = streamingClients();
        if (streaming.isEmpty()) {
            windows.idle();
        }
        if (resolution == null) {
            return;
        }
        if (!streaming.isEmpty()) {
            handOver(streaming);
            return;
        }
        setModeOwner(null);
        if (resolution.changed()) {
            resolution.restore();
        }
        if (power != null && (turnOffWhenIdle || power.anyTurnedOff())) {
            try {
                power.idle(outputs.sunshineOutput().orElse(null), turnOffWhenIdle);
            } catch (IOException e) {
                LOG.log(Level.FINE, "cannot tell which monitor Sunshine streams", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Sets the mode for the next client in line if the owner stopped streaming. Only after Orbit set the mode. */
    private void handOver(List<ClientView> streaming) {
        String owner = modeOwner();
        if (owner == null || !matchResolution || streaming.stream().anyMatch(c -> c.agentId().equals(owner))) {
            return;
        }
        ClientView next = streaming.getFirst();
        try {
            Optional<String> output = outputs.sunshineOutput();
            if (output.isPresent()) {
                resolution.apply(output.get(), next.settings());
            }
            LOG.info(() -> "host monitor now follows " + next.hostname() + ", which still streams");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "cannot set the host monitor for " + next.hostname(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        setModeOwner(next.agentId());
    }

    /** Restores the original mode and turns monitors Orbit turned off back on; called when the host exits. */
    @Override
    public void close() {
        worker.shutdownNow();
        windows.idle();
        if (resolution != null) {
            resolution.restore();
        }
        if (power != null) {
            power.restoreAll();
        }
    }

    // ---- helpers ----

    private String hostname(String agentId) {
        return registry.client(agentId).map(ClientView::hostname).orElse(agentId);
    }

    /**
     * Waits for a command, turning errors and timeouts into a failed result. Each command times out by itself; this
     * is only a backstop, past the longest of them.
     */
    static Message.CommandResult await(CompletableFuture<Message.CommandResult> command) throws InterruptedException {
        try {
            return command.get(AgentRegistry.START_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return Message.CommandResult.failure(null, AgentRegistry.rootMessage(e));
        } catch (TimeoutException e) {
            return Message.CommandResult.failure(null, "the client did not answer");
        }
    }
}
