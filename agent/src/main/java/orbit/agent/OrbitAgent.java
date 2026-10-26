package orbit.agent;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import orbit.protocol.ClientState;
import orbit.protocol.JsonFiles;
import orbit.protocol.Message;
import orbit.protocol.Protocol;
import orbit.protocol.StreamSettings;

/**
 * Agent daemon: connects to the Orbit host, reports whether this client can be streamed to, and runs the host's
 * commands through Moonlight. Commands run one at a time on a worker thread.
 */
public final class OrbitAgent implements HostConnection.Handler {

    private static final Logger LOG = Logger.getLogger(OrbitAgent.class.getName());
    private static final Duration STATUS_KEEPALIVE = Duration.ofSeconds(30);
    /** How often the idle screen checks for streams started outside Orbit. */
    private static final Duration IDLE_CHECK = Duration.ofSeconds(3);
    /**
     * When the next periodic pairing check is due after one: by the next {@link #STATUS_KEEPALIVE} tick, a few
     * seconds early, so a check just after a tick isn't pushed to the one after.
     */
    private static final Duration PAIR_RECHECK = STATUS_KEEPALIVE.minusSeconds(5);
    /** On the idle screen and in the tray while the host's stop runs, for a stream whose app isn't known. */
    static final String STOPPING_TEXT = "Stopping the stream…";

    /** Where the agent is in connecting to the host, for the idle screen. */
    enum Link { SEARCHING, PENDING, REJECTED, APPROVED }

    private final Path configFile;
    private final String hostname;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(daemon("orbit-agent-worker"));
    private final ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor(daemon("orbit-agent-tick"));
    private final SecureRandom random = new SecureRandom();
    private final MoonlightController moonlight;
    private final HostConnection connection;
    /** Written on the worker thread (and in the constructor); read when saying hello on the connection thread. */
    private volatile String moonlightVersion;
    /** The codecs this machine decodes in hardware, for the host's stream settings; null if it can't tell. */
    private volatile List<String> hardwareDecoders;
    /** No Moonlight could be found or started; checked again every {@link #STATUS_KEEPALIVE} until it can. */
    private volatile boolean moonlightMissing;
    /** Sunshine's verdict on the pairing in progress, completed by the host's {@code pairingDone}; else null. */
    private volatile CompletableFuture<Boolean> pairingVerdict;
    private volatile String pairingCmdId;
    /** The host said Sunshine isn't running; assumed running until it says so. Worker thread only. */
    private boolean sunshineDown;
    /** Whether this client is paired, from Moonlight's checks; worker thread only. */
    private final PairingCheck pairing;
    private final IdleScreen.Display idle;
    /**
     * A text console, with the framebuffer idle screen: Moonlight's own screens are kept off the display, and the
     * idle screen shows "Starting <app>…" until the stream's picture replaces it.
     */
    private final boolean console;
    /** Counts the host's start commands, so a delayed stop only ever stops the stream it was meant for. */
    private long streamsStarted;
    /** The app a start command is starting, until Moonlight runs; else null. */
    private String startingApp;
    /** The tray icon on a desktop (instead of the idle screen), or null. */
    private final AgentTray tray;
    /** Switches the TV to this client before streams; null when HDMI-CEC is off or unavailable. */
    private final HdmiCec cec;
    /** The TV remote's input devices; null until found (HDMI-CEC on). */
    private volatile RemoteKeys remoteKeys;
    /** The remote during streams, through virtual devices; null without the remote or {@code /dev/uinput}. */
    private volatile StreamRemote streamRemote;
    /** The remote's keys go to {@link #streamRemote}: a stream runs. */
    private volatile boolean remoteGrabbed;
    /** Logged once: the remote couldn't be taken while idle, so the console gets its keys as typing too. */
    private boolean idleGrabFailed;

    // Touched only on the worker thread.
    private AgentConfig config;
    private HostLocator.Target target;
    private boolean approved;
    private StreamSettings settings = StreamSettings.DEFAULT;
    private Message.Status lastStatus;
    private Link link = Link.SEARCHING;
    private String rejectReason;
    /** The host takes {@link Message.StreamRequest}s (its welcome said so). */
    private boolean hostTakesRequests;
    /** The host also sends its apps, takes a stream request's app, and takes stop requests. */
    private boolean hostTakesControl;
    /** The host gives focus back to the stream when asked ({@link Message.FocusRequest}); read on other threads. */
    private volatile boolean hostTakesFocus;
    /** The host shows what the remote does ({@link Message.RemoteAction}); read on other threads. */
    private volatile boolean hostShowsActions;
    /** The host answers {@link Message.StopCheckRequest}s. */
    private boolean hostChecksStops;
    /** A stop with the remote or the tray: asks first, then has the host stop the stream. */
    private final StopFlow stops = new StopFlow(new StopActions());
    /** The remote was grabbed for the stop question alone (no stream remote), and is given back with it. */
    private boolean grabbedForQuestion;
    /**
     * The host's stop runs: Sunshine quits the app, which can take a while (its undo commands, e.g. stopping Zwift's
     * container), and the worker waits for it. Read on the remote's reader thread, which drops keys meanwhile.
     */
    private volatile boolean stopping;
    /** The app the host's stop quits, while it runs and the app is known; else null. */
    private String stoppingApp;
    /** The TV remote while idle: the app list, and the notices on the idle screen. */
    private final IdleRemote idleRemote = new IdleRemote(new IdleRemoteActions(), System::nanoTime);

    OrbitAgent(Path configFile, AgentConfig config, String hostname) {
        this.configFile = configFile;
        this.config = config;
        this.hostname = hostname;
        boolean console = GraphicsEnvironment.isHeadless();
        this.moonlight = new MoonlightController(List.of(MoonlightLocator.EXECUTABLES.getFirst()),
                console ? MoonlightController.consoleLauncher() : MoonlightController.systemLauncher(),
                () -> worker.execute(() -> {
                    stops.closeQuestion(false);
                    grabRemote(false);
                    reportStatus(true);
                }));
        this.pairing = new PairingCheck(moonlight::checkPairing, PAIR_RECHECK);
        checkMoonlight(true);
        if (!moonlightMissing) {
            try {
                moonlight.closeLeftoverStreams();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        this.connection = new HostConnection(new HostLocator(config.host(), config.hostName()), this);
        this.cec = HdmiCec.fromConfig(config.hdmiCec()).orElse(null);
        // A desktop gets the tray icon, and the idle screen at startup only if set ("fullscreen", e.g. by the TV);
        // the tray can show it any time. A text console gets the framebuffer idle screen.
        this.console = console;
        if (!console) {
            this.tray = AgentTray.install(new TrayActions()).orElse(null);
            // Without a tray nothing could bring it back, so it then returns after each stream as before.
            this.idle = IdleScreen.window(config.idleImagePath(), hostname, IdleScreen.versionLine(moonlightVersion),
                    config.animateIdle(), config.idleScreenAtStartup(), tray != null || !config.idleScreenAtStartup());
        } else {
            this.tray = null;
            this.idle = IdleScreen.console(config.idleImagePath(), hostname,
                    IdleScreen.versionLine(moonlightVersion), config.animateIdle());
        }
    }

    /** The tray menu's actions: from the event thread onto the worker. */
    private final class TrayActions implements AgentTray.Actions {
        @Override
        public void stopStream() {
            worker.execute(() -> stops.ask(false));
        }

        @Override
        public void setStreamsAllowed(boolean allowed) {
            worker.execute(() -> {
                saveConfig(config.withAllowStreams(allowed), "the streams setting");
                LOG.info(allowed ? "streams from the host allowed again" : "streams from the host turned off");
                reportStatus(true);
            });
        }

        @Override
        public void showIdleScreen() {
            worker.execute(() -> {
                idle.reveal();
                updateIdleScreen();
            });
        }

        @Override
        public void setIdleScreenAtStartup(boolean show) {
            worker.execute(() -> {
                saveConfig(config.withIdleScreenAtStartup(show), "the idle screen setting");
                LOG.info(show ? "idle screen shown at startup from now on" : "idle screen no longer shown at startup");
                updateIdleScreen();
            });
        }

        @Override
        public void quit() {
            LOG.info("quit from the tray");
            System.exit(0);
        }
    }

    /** What the stop at the client needs from the agent; on the worker thread. */
    private final class StopActions implements StopFlow.Agent {
        @Override
        public boolean hostConnected() {
            return approved && connection.isConnected();
        }

        @Override
        public boolean hostStops() {
            return hostTakesControl;
        }

        @Override
        public boolean hostChecksStops() {
            return hostChecksStops;
        }

        @Override
        public boolean hostShowsActions() {
            return hostShowsActions;
        }

        @Override
        public long streamNumber() {
            return streamsStarted;
        }

        @Override
        public boolean streaming() {
            return moonlight.isStreaming();
        }

        @Override
        public void send(Message.FromAgent message) {
            connection.send(message);
        }

        @Override
        public void later(Duration delay, Runnable action) {
            ticker.schedule(() -> worker.execute(action), delay.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void disconnect(String how) {
            disconnectStream(how);
        }

        @Override
        public boolean remoteFound() {
            return OrbitAgent.this.remoteFound();
        }

        @Override
        public void takeRemoteForQuestion() {
            StreamRemote remote = streamRemote;
            if (remote != null && remoteGrabbed) {
                remote.pause();
            }
            RemoteKeys keys = remoteKeys;
            if (!remoteGrabbed && keys != null) {
                grabbedForQuestion = keys.grab(true);
            }
        }

        @Override
        public void giveRemoteBack() {
            if (grabbedForQuestion) {
                grabbedForQuestion = false;
                RemoteKeys keys = remoteKeys;
                // Given back to Moonlight; once the stream has ended, it stays the agent's (grabRemote).
                if (keys != null && moonlight.isStreaming()) {
                    keys.grab(false);
                }
            }
        }

        @Override
        public boolean askHere(String warning, Runnable stop) {
            if (tray == null) {
                return false;
            }
            tray.askToStop(warning, stop);
            return true;
        }
    }

    /** Ends this client's stream, leaving the app running on the host. */
    private void disconnectStream(String how) {
        try {
            // Only this client's stream: quitting the app would end other clients' streams of it too.
            moonlight.disconnect();
            LOG.info("stream stopped " + how);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        reportStatus(true);
    }

    /**
     * A key event from the TV remote, on its reader thread. Its Stop ends the stream. While idle, its Play/Pause, OK
     * or arrows open the list of Sunshine's apps (or, with an older host, Play/Pause and OK start the last app), where
     * up and down move, OK or Play starts and Stop closes.
     */
    private void onRemoteEvent(RemoteKeys.Event event) {
        if (stopping) {
            // Neither for the app that's closing nor, once it's closed, for the idle screen.
            return;
        }
        if (event.value() == 1 && !moonlight.isStreaming()) {
            // Any key, also those Orbit doesn't use, so it's plain to see which ones the TV passes on.
            idle.flashRemote();
        }
        if (stops.questionOpen()) {
            event.pressed().ifPresent(key -> worker.execute(() -> stops.questionKey(key)));
            return;
        }
        StreamRemote remote = streamRemote;
        if (remoteGrabbed && remote != null) {
            remote.event(event.code(), event.value());
        } else {
            event.pressed().ifPresent(key -> worker.execute(() -> onRemoteKey(key)));
        }
    }

    /**
     * The remote's mouse and keyboard for streams. Without {@code /dev/uinput} the remote's keys reach Moonlight as
     * they are during streams, so arrows still work but there's no mouse.
     */
    private StreamRemote createStreamRemote() {
        VirtualInput keyboard = null;
        try {
            keyboard = VirtualInput.keyboard();
            RemoteDevices devices = new RemoteDevices(keyboard, VirtualInput.mouse());
            LOG.info("TV remote: Play/Pause during a stream switches the arrows between keys and a mouse");
            return new StreamRemote(devices, new RemoteActions(), ticker, System::nanoTime);
        } catch (IOException e) {
            if (keyboard != null) {
                keyboard.close();
            }
            LOG.info("TV remote: no mouse mode during streams (" + e.getMessage() + "); allow /dev/uinput for the input"
                    + " group, as install-agent-tty1.sh does");
            return null;
        }
    }

    /** Where the remote's keys and pointer go during a stream: the virtual keyboard, and the mouse for its buttons. */
    private record RemoteDevices(VirtualInput keys, VirtualInput mouse) implements StreamRemote.Output {
        @Override
        public void key(int code, int value) throws IOException {
            (code == VirtualInput.BTN_LEFT || code == VirtualInput.BTN_RIGHT ? mouse : keys).key(code, value);
        }

        @Override
        public void move(int dx, int dy) throws IOException {
            mouse.move(dx, dy);
        }
    }

    /** What the remote has the agent and the host do during a stream; called on its reader thread. */
    private final class RemoteActions implements StreamRemote.Actions {
        @Override
        public void stop() {
            worker.execute(() -> onRemoteKey(RemoteKeys.Key.STOP));
        }

        @Override
        public boolean focus(boolean pointer) {
            if (!hostTakesFocus) {
                return false;
            }
            worker.execute(() -> connection.send(new Message.FocusRequest(pointer)));
            return true;
        }

        @Override
        public void modeChanged(StreamRemote.Mode mode) {
            if (hostShowsActions) {
                String action = mode == StreamRemote.Mode.MOUSE
                        ? Message.RemoteAction.MOUSE_MODE : Message.RemoteAction.KEY_MODE;
                worker.execute(() -> connection.send(new Message.RemoteAction(action)));
            }
        }
    }

    /**
     * Who gets the remote's keys. The kernel makes its input device a keyboard, so whatever isn't grabbed also goes to
     * the console (or the desktop's focused window) as typing. While idle, the keys are the agent's alone. During a
     * stream they're {@link #streamRemote}'s, which passes them on to Moonlight; without it (no {@code /dev/uinput}),
     * Moonlight reads the device itself, so it's let go.
     *
     * @param streaming a stream has started (true) or ended (false)
     */
    private void grabRemote(boolean streaming) {
        StreamRemote remote = streamRemote;
        RemoteKeys keys = remoteKeys;
        if (keys == null || !keys.found()) {
            return;
        }
        if (!streaming) {
            if (remoteGrabbed) {
                remoteGrabbed = false;
                remote.reset();
            }
            if (!keys.grab(true) && !idleGrabFailed) {
                idleGrabFailed = true;
                LOG.warning("TV remote: cannot take the remote while idle; the console gets its keys too");
            }
            return;
        }
        if (remote == null) {
            keys.grab(false);
            return;
        }
        if (keys.grab(true)) {
            remoteGrabbed = true;
        } else {
            keys.grab(false);
            LOG.warning("TV remote: cannot take the remote from Moonlight; its keys go to Moonlight as they are");
        }
    }

    private void onRemoteKey(RemoteKeys.Key key) {
        if (moonlight.isStreaming()) {
            // Everything but Stop is Moonlight's, which passes it on to the host.
            if (key == RemoteKeys.Key.STOP) {
                stops.ask(true);
            }
            return;
        }
        idleRemote.key(key);
    }

    /** What the remote needs from the agent while idle; called on the worker thread. */
    private final class IdleRemoteActions implements IdleRemote.Agent {
        @Override
        public String requestRefusal() {
            return OrbitAgent.this.requestRefusal();
        }

        @Override
        public boolean hostTakesControl() {
            return hostTakesControl;
        }

        @Override
        public void send(Message.FromAgent message) {
            connection.send(message);
        }

        @Override
        public void updateIdleScreen() {
            OrbitAgent.this.updateIdleScreen();
        }
    }

    /** Why the TV remote's Play can't ask for a stream now, for the idle screen; null if it can. */
    private String requestRefusal() {
        if (!connection.isConnected()) {
            return "Not connected to the Orbit host";
        }
        if (!approved) {
            return "Not approved in Orbit yet";
        }
        if (!hostTakesRequests) {
            return "Update Orbit on the host to start streams with the remote";
        }
        if (!config.streamsAllowed()) {
            return "Streams are turned off on this client";
        }
        if (moonlightMissing) {
            return "Moonlight isn't installed";
        }
        if (Boolean.FALSE.equals(pairing.paired())) {
            return "Not paired yet. Pair this client in Orbit";
        }
        return null;
    }

    /** Uses {@code updated} from now on, and saves it; a failed save only costs the setting at the next start. */
    private void saveConfig(AgentConfig updated, String what) {
        config = updated;
        try {
            config.save(configFile);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "cannot save " + what + " to " + configFile, e);
        }
    }

    private void notifyTray(String title, String message) {
        if (tray != null) {
            tray.notify(title, message);
        }
    }

    /**
     * Finds Moonlight (the configured command, else the detected one) and reads its version. Sets
     * {@link #moonlightMissing} if there is none or it can't be started.
     *
     * @param startup log what was found, and log loudly if nothing was
     */
    private void checkMoonlight(boolean startup) {
        List<String> configured = config.moonlightCommand();
        Optional<List<String>> command = configured != null ? Optional.of(configured) : MoonlightLocator.detect();
        if (command.isEmpty()) {
            moonlightMissing = true;
            if (startup) {
                LOG.severe("Moonlight not found: tried " + String.join(", ", MoonlightLocator.EXECUTABLES)
                        + " on PATH and the Flatpak " + MoonlightLocator.FLATPAK_ID + ". Install moonlight-qt,"
                        + " or set \"moonlightCommand\" in agent.json to the command that starts it."
                        + " Checking again every " + STATUS_KEEPALIVE.toSeconds() + " s.");
            }
            return;
        }
        String described = String.join(" ", command.get());
        moonlight.setCommand(command.get());
        try {
            moonlightVersion = moonlight.version().orElse(null);
            moonlightMissing = false;
            if (startup) {
                LOG.info(() -> (configured != null ? "running Moonlight as configured: " : "found Moonlight: ")
                        + described);
            }
        } catch (IOException e) {
            moonlightMissing = true;
            if (startup) {
                LOG.severe("cannot start Moonlight (" + described + "): " + e.getMessage()
                        + ". Checking again every " + STATUS_KEEPALIVE.toSeconds() + " s.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** While Moonlight is missing: looks again, and once it's there, checks pairing and tells the host. */
    private void recheckMoonlight() {
        if (!moonlightMissing) {
            return;
        }
        checkMoonlight(false);
        if (!moonlightMissing) {
            LOG.info(() -> "Moonlight is now available: " + String.join(" ", moonlight.command())
                    + " (" + Objects.requireNonNullElse(moonlightVersion, "version unknown") + ")");
            idle.setVersions(IdleScreen.versionLine(moonlightVersion));
            if (target != null) {
                refreshPaired();
            }
            reportStatus(true);
        }
    }

    public static void main(String[] args) throws Exception {
        // E.g. a worker task that throws: into the agent's log rather than bare stderr.
        Thread.setDefaultUncaughtExceptionHandler((thread, e) ->
                LOG.log(Level.SEVERE, "uncaught error on thread " + thread.getName(), e));
        Path configFile = args.length > 0 ? Path.of(args[0]) : JsonFiles.configDir().resolve(AgentConfig.FILE_NAME);
        AgentConfig config = AgentConfig.load(configFile);
        OrbitAgent agent = new OrbitAgent(configFile, config, InetAddress.getLocalHost().getHostName());
        LOG.info(() -> "orbit-agent " + AgentInfo.VERSION + " (" + config.agentId() + ") starting (config "
                + configFile + ", moonlight " + Objects.requireNonNullElse(agent.moonlightVersion, "version unknown")
                + ")");
        Runtime.getRuntime().addShutdownHook(new Thread(agent.connection::stop));
        agent.run();
    }

    void run() {
        if (cec != null) {
            worker.execute(() -> {
                cec.checkAtStartup();
                remoteKeys = RemoteKeys.listen(cec.adapterNames(), this::onRemoteEvent);
                if (remoteKeys.found()) {
                    streamRemote = createStreamRemote();
                    grabRemote(moonlight.isStreaming());
                    reportStatus(false);
                }
            });
        }
        ticker.scheduleAtFixedRate(() -> worker.execute(() -> {
                    recheckMoonlight();
                    // Pairing can change without Orbit: paired in Moonlight's own window, or unpaired in Sunshine's
                    // web UI. So it's checked again while idle.
                    if (target != null && approved && !moonlight.isStreaming() && pairing.due()) {
                        refreshPaired();
                    }
                    reportStatus(true);
                }),
                STATUS_KEEPALIVE.toSeconds(), STATUS_KEEPALIVE.toSeconds(), TimeUnit.SECONDS);
        ticker.scheduleAtFixedRate(() -> worker.execute(this::updateIdleScreen),
                0, IDLE_CHECK.toSeconds(), TimeUnit.SECONDS);
        // Before the first hello, which reports them.
        hardwareDecoders = VideoDecoders.probe().orElse(null);
        LOG.info(() -> hardwareDecoders == null ? "cannot tell which video codecs decode in hardware here"
                : hardwareDecoders.isEmpty() ? "no video codec decodes in hardware here; Moonlight decodes in software"
                : "video codecs decoded in hardware: " + String.join(", ", hardwareDecoders));
        connection.run();
    }

    // ---- connection callbacks (WebSocket thread; hand everything to the worker) ----

    @Override
    public Message.Hello connected(HostLocator.Target target) {
        worker.execute(() -> {
            this.target = target;
            this.approved = false;
            this.hostTakesRequests = false;
            this.hostTakesControl = false;
            this.hostTakesFocus = false;
            this.hostShowsActions = false;
            this.hostChecksStops = false;
            stops.reset();
            idleRemote.reset();
            this.lastStatus = null;
            // A new connection: assume Sunshine runs until this host says otherwise (before its welcome).
            this.sunshineDown = false;
            updateIdleScreen();
        });
        return new Message.Hello(Protocol.VERSION, config.agentId(), hostname, config.token(), moonlightVersion,
                AgentInfo.VERSION, hardwareDecoders);
    }

    @Override
    public void received(Message.FromHost message) {
        // Handled here, not on the worker: the worker is busy running the pairing this is about.
        if (message instanceof Message.PairingDone done) {
            CompletableFuture<Boolean> verdict = pairingVerdict;
            if (verdict != null && done.cmdId().equals(pairingCmdId)) {
                verdict.complete(done.accepted());
            }
            return;
        }
        worker.execute(() -> handle(message));
    }

    @Override
    public void disconnected() {
        worker.execute(() -> {
            approved = false;
            if (link != Link.REJECTED) {
                link = Link.SEARCHING;
            }
            // Orbit is how the stream gets managed; without the host, end the stream Orbit started.
            try {
                if (moonlight.stopOwnStream()) {
                    LOG.info("lost the Orbit host; stopped the stream");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            updateIdleScreen();
        });
    }

    // ---- worker thread ----

    private void handle(Message.FromHost message) {
        try {
            switch (message) {
                case Message.Welcome w -> onWelcome(w);
                case Message.Pending p -> {
                    if (link != Link.PENDING) {
                        notifyTray("Waiting for approval", "Approve this client in Orbit on " + target.address());
                    }
                    link = Link.PENDING;
                    LOG.info("waiting for approval on the Orbit host");
                }
                case Message.Rejected r -> {
                    approved = false;
                    link = Link.REJECTED;
                    rejectReason = r.reason();
                }
                case Message.ApplySettings a -> {
                    settings = a.settings();
                    LOG.info(() -> "stream settings now " + settings);
                }
                case Message.StartStream s -> runCommand(s.cmdId(), () -> {
                    streamsStarted++;
                    if (!config.streamsAllowed()) {
                        throw new IllegalStateException(hostname + " doesn't allow streams right now; that's"
                                + " turned off in its tray icon");
                    }
                    if (Boolean.FALSE.equals(pairing.paired())) {
                        // moonlight-qt would show "pair first" and wait for someone to close it.
                        throw new IllegalStateException("not paired yet; pair this client in Orbit first");
                    }
                    if (console) {
                        startingApp = s.app();
                        updateIdleScreen();
                    }
                    try {
                        pairing.beforeStream(target.address());
                        if (cec != null) {
                            // Before Moonlight starts, so it finds the TV on and its display modes.
                            cec.switchTvToThisInput();
                        }
                        settings = s.settings();
                        if (!console) {
                            idle.hide(); // Moonlight's window must not open behind it
                        }
                        moonlight.startStream(target.address(), s.app(), s.settings(), s.quitAppOnFailure());
                    } finally {
                        // Once Moonlight runs, the idle screen stops redrawing but its last frame, "Starting…", stays
                        // in the framebuffer until the stream's picture replaces it.
                        startingApp = null;
                    }
                    grabRemote(true);
                    notifyTray("Stream started", "Orbit on " + target.address() + " started " + s.app() + " here");
                });
                case Message.StopStream s when s.disconnectOnly() -> runCommand(s.cmdId(), moonlight::disconnect);
                case Message.StopStream s -> stopForHost(s.cmdId());
                case Message.BeginPairing b -> runCommand(b.cmdId(), () -> pair(b.cmdId()));
                case Message.SunshineState st -> onSunshineState(st.running());
                case Message.CheckPairing c -> {
                    LOG.info("the Orbit host asks to check pairing now");
                    refreshPaired();
                    reportStatus(false);
                }
                case Message.AppList list -> idleRemote.appList(list, moonlight.isStreaming());
                case Message.StopCheck check -> stops.onStopCheck(check);
                case Message.StreamRequestRefused r -> {
                    idleRemote.requestRefused(r.reason());
                    notifyTray("Couldn't start a stream", r.reason());
                }
                case Message.PairingDone d -> {
                    // Handled in received(), while the pairing it's about is still running.
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "error handling " + message, e);
        }
        updateIdleScreen();
    }

    private void onWelcome(Message.Welcome welcome) {
        approved = true;
        hostTakesRequests = welcome.streamRequests();
        hostTakesControl = welcome.streamControl();
        hostTakesFocus = welcome.focusRequests();
        hostShowsActions = welcome.remoteActions();
        hostChecksStops = welcome.stopChecks();
        link = Link.APPROVED;
        rejectReason = null;
        if (welcome.settings() != null) {
            settings = welcome.settings();
        }
        if (welcome.token() != null) {
            config = config.withToken(welcome.token());
            try {
                config.save(configFile);
                LOG.info("approved by the Orbit host; token saved");
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "cannot save token to " + configFile + "; approval will be lost on restart", e);
            }
        }
        refreshPaired();
        reportStatus(true);
    }

    private interface Action {
        void run() throws Exception;
    }

    /** Runs a host command and answers it with a {@link Message.CommandResult}. */
    private void runCommand(String cmdId, Action action) {
        Message.CommandResult result;
        if (!approved || target == null) {
            result = Message.CommandResult.failure(cmdId, "agent is not approved");
        } else if (moonlightMissing) {
            result = Message.CommandResult.failure(cmdId, "Moonlight isn't installed on " + hostname);
        } else {
            try {
                action.run();
                result = Message.CommandResult.success(cmdId);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.log(Level.WARNING, "command failed", e);
                result = Message.CommandResult.failure(cmdId, e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }
        connection.send(result);
        reportStatus(false);
    }

    /**
     * Runs the host's stop, showing "Stopping <app>…" until it's done. Moonlight closes the stream as soon as
     * Sunshine ends the session, but {@code moonlight quit} returns only once Sunshine has quit the app, and until then
     * the console would show the idle screen's last frame, left in the framebuffer from before the stream.
     */
    private void stopForHost(String cmdId) {
        long started = System.nanoTime();
        stoppingApp = moonlight.currentApp();
        stopping = true;
        updateIdleScreen();
        try {
            runCommand(cmdId, () -> moonlight.stopStream(target.address()));
        } finally {
            stopping = false;
            stoppingApp = null;
        }
        LOG.info(() -> "stream stopped for the Orbit host; Sunshine quit the app in "
                + Duration.ofNanos(System.nanoTime() - started).toMillis() + " ms");
    }

    private void pair(String cmdId) throws Exception {
        String pin = String.format("%04d", random.nextInt(10_000));
        MoonlightController.PairOutcome outcome;
        CompletableFuture<Boolean> verdict = new CompletableFuture<>();
        pairingCmdId = cmdId;
        pairingVerdict = verdict;
        try {
            outcome = moonlight.pair(target.address(), pin, () -> connection.send(new Message.PairPin(cmdId, pin)),
                    verdict);
        } catch (IOException e) {
            refreshPaired();
            throw e;
        } finally {
            pairingVerdict = null;
            pairingCmdId = null;
        }
        pairing.set(true, null);
        LOG.info((outcome == MoonlightController.PairOutcome.ALREADY_PAIRED ? "already paired with " : "paired with ")
                + target.address());
        notifyTray("Paired", "Ready to stream from Orbit on " + target.address());
    }

    /** The host's word on Sunshine: while it's down, pairing isn't checked; once it's back, it's checked at once. */
    private void onSunshineState(boolean running) {
        if (!running) {
            if (!sunshineDown) {
                LOG.warning("the Orbit host says Sunshine isn't running; pairing is checked again once it is");
            }
            sunshineDown = true;
        } else if (sunshineDown) {
            sunshineDown = false;
            LOG.info("the Orbit host says Sunshine runs again; checking pairing");
            if (approved && target != null) {
                refreshPaired();
            }
        }
        reportStatus(true);
    }

    /**
     * Checks whether this client is paired. While the host says Sunshine isn't running, {@code moonlight list} would
     * fail as if unpaired (after a long timeout), so the pairing state is kept as it was until Sunshine is back.
     */
    private void refreshPaired() {
        if (moonlightMissing) {
            pairing.set(null, null);
            return;
        }
        if (sunshineDown) {
            return;
        }
        try {
            pairing.refresh(target.address());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ClientState currentState() {
        if (moonlight.isStreaming()) {
            return ClientState.STREAMING;
        }
        Boolean paired = pairing.paired();
        if (paired == null) {
            return ClientState.ERROR;
        }
        return paired ? ClientState.AVAILABLE : ClientState.UNPAIRED;
    }

    /** Shows the idle screen with the current status, or hides it while a stream runs; updates the tray icon. */
    private void updateIdleScreen() {
        boolean streaming = moonlight.isStreaming();
        idleRemote.expire(streaming);
        String notice = idleRemote.notice();
        String text = stopping ? stoppingText(stoppingApp) : startingApp != null ? "Starting " + startingApp + "…"
                : notice != null ? notice : idleText(link, rejectReason, currentState(), moonlightMissing,
                sunshineDown, pairing.problem(), config.streamsAllowed());
        idle.setRemote(remoteFound());
        if (streaming && !stopping) {
            idle.hide();
        } else {
            idle.show(text, idleRemote.menu());
        }
        if (tray != null) {
            boolean running = streaming && !stopping;
            tray.show(new AgentTray.State(trayText(text, running, moonlight.currentApp()), running,
                    config.streamsAllowed(), config.idleScreenAtStartup()));
        }
    }

    /** The TV remote's input device is being read: the client can be used with the remote. */
    private boolean remoteFound() {
        RemoteKeys keys = remoteKeys;
        return keys != null && keys.found();
    }

    /** On the idle screen and in the tray while the host's stop quits {@code app} (null: not known). */
    static String stoppingText(String app) {
        return app != null ? "Stopping " + app + "…" : STOPPING_TEXT;
    }

    /** The tray's status line: the idle screen's, naming the app while streaming. */
    static String trayText(String idleText, boolean streaming, String app) {
        if (streaming) {
            return app != null ? "Streaming " + app : "Streaming";
        }
        return idleText;
    }

    /**
     * The status line on the idle screen and in the tray.
     *
     * @param streamsAllowed false while streams from the host are turned off (the tray's "Don't allow streams")
     */
    static String idleText(Link link, String rejectReason, ClientState state, boolean moonlightMissing,
            boolean sunshineDown, String problem, boolean streamsAllowed) {
        if (moonlightMissing) {
            return "Moonlight isn't installed. Install moonlight-qt on this client";
        }
        if (sunshineDown && link == Link.APPROVED) {
            return "Sunshine isn't running on the host";
        }
        return switch (link) {
            case SEARCHING -> "Looking for the Orbit host…";
            case PENDING -> "Waiting for approval in Orbit";
            case REJECTED -> "Not allowed by the Orbit host" + (rejectReason != null ? ": " + rejectReason : "");
            case APPROVED -> switch (state) {
                case AVAILABLE -> streamsAllowed ? "Ready to stream" : "Not allowing streams";
                case STREAMING -> "Streaming";
                case UNPAIRED -> "Not paired yet. Pair this client in Orbit";
                case ERROR -> problem != null ? problem : "Moonlight isn't working. See the agent log";
            };
        };
    }

    /** Sends the status if it changed, or always when {@code force} (keepalive). */
    private void reportStatus(boolean force) {
        updateIdleScreen();
        if (!approved || !connection.isConnected()) {
            return;
        }
        ClientState state = currentState();
        Message.Status status = new Message.Status(state,
                state == ClientState.STREAMING ? moonlight.currentApp() : null, moonlightMissing, moonlightVersion,
                state == ClientState.ERROR ? pairing.problem() : null, !config.streamsAllowed(), remoteFound());
        if (force || !status.equals(lastStatus)) {
            connection.send(status);
            lastStatus = status;
        }
    }

    private static ThreadFactory daemon(String name) {
        return Thread.ofPlatform().daemon().name(name).factory();
    }
}
