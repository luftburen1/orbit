package orbit.host;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;
import orbit.protocol.JsonFiles;
import orbit.protocol.Message;

/** Host entry point: agent server, mDNS announcement, Sunshine access and the desktop UI. */
public final class OrbitHost {

    private static final Logger LOG = Logger.getLogger(OrbitHost.class.getName());
    /** How often Orbit checks that Sunshine answers, so clients show "Sunshine not running" while it doesn't. */
    private static final Duration SUNSHINE_WATCH = Duration.ofSeconds(5);
    /** Sunshine's paired clients are asked for every this many checks (30 s), as they rarely change. */
    private static final int PAIRED_EVERY = 6;
    /** While something streams: every 10 s, so a client unpaired in Sunshine loses its stream soon. */
    private static final int PAIRED_EVERY_WHILE_STREAMING = 2;
    /** Grace before turning the monitor off at startup, so clients that were streaming can reconnect first. */
    private static final Duration STARTUP_GRACE = Duration.ofSeconds(20);

    private final Path configFile;
    private final AgentRegistry registry;
    private final AgentServer server;
    private final MdnsAdvertiser mdns = new MdnsAdvertiser();
    private final StreamDirector director;
    /** What Orbit can do with this desktop beyond streaming; {@link HostDesktop#NONE} where it has no integration. */
    private final HostDesktop desktop;
    /** "Starting <app>" on Sunshine's monitor until the app's window comes. */
    private final LaunchSplash splash = new LaunchSplash();
    private final StartNotice notice = new StartNotice();
    private volatile HostConfig config;
    private final MonitorMemory monitorMemory;
    private volatile SunshineClient sunshine;
    private final ScheduledExecutorService sunshineWatch = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("orbit-sunshine-watch").factory());
    /** Sunshine checks so far, for {@link #PAIRED_EVERY}. Watch thread only. */
    private int checks;
    /**
     * This Sunshine doesn't list waiting pairing requests (older than 2026), so they aren't asked for again until the
     * login changes.
     */
    private volatile boolean noPendingPairings;
    /** Sunshine's paired names at the last check, or null before the first. Watch thread only. */
    private Set<String> lastPaired;
    /** Sunshine's limit as last read, for the buttons and tray; unlimited until known. */
    private volatile int maxStreams = Integer.MAX_VALUE;
    /** Set on the event thread before the window opens. */
    private ThemeSwitcher themes;

    OrbitHost(Path configDir) throws IOException {
        this.configFile = configDir.resolve(HostConfig.FILE_NAME);
        this.config = HostConfig.load(configFile);
        this.registry = new AgentRegistry(configDir.resolve("clients.json"), Clock.systemUTC());
        this.monitorMemory = new MonitorMemory(configDir.resolve("monitors.json"));
        this.registry.load();
        this.server = new AgentServer(registry);
        this.sunshine = newSunshineClient(config);
        this.desktop = HostDesktop.detect();
        LOG.info(() -> "desktop: " + desktop.name() + describeParts(desktop));
        HostResolution resolution = desktop.display().map(HostResolution::new).orElse(null);
        this.director = new StreamDirector(registry, resolution,
                () -> StreamDirector.sunshineOutput(sunshine), () -> SunshineClient.ensureRunning(config.sunshineUri()),
                StreamDirector.RESTORE_DELAY);
        director.rememberTurnedOffMonitorsIn(configDir.resolve("monitor-power.json"));
        director.rememberAppIdsIn(configDir.resolve("app-ids.json"));
        director.setStreamLimit(this::readMaxStreams);
        director.setPairingRefresh(this::refreshSunshinePairings);
        director.setRunningApp(() -> {
            SunshineClient s = sunshine;
            return s != null ? s.currentGame() : 0;
        });
        registry.setAppSource(() -> requireSunshine().listApps());
        desktop.windows().ifPresent(windows -> {
            director.setWindowPlacer(new StreamDirector.WindowPlacer() {
                @Override
                public void streamStarting(String output, String app) {
                    HostConfig.Stream settings = config.stream();
                    if (!settings.placeWindows()) {
                        return;
                    }
                    Optional<SunshineClient.App> known = sunshineApp(app);
                    // Assumed to start something when the app list can't be read.
                    if (known.map(SunshineClient.App::launches).orElse(true)) {
                        if (windows.placeApp(output, notice::close, splash::close)) {
                            if (settings.launchSplash()) {
                                splash.show(app, known.flatMap(SunshineClient.App::cover));
                            }
                            if (settings.startNotice()) {
                                notice.show(app);
                            }
                        }
                    } else {
                        windows.focusDesktop(output);
                    }
                }

                @Override
                public void idle() {
                    splash.close();
                    notice.close();
                    desktop.notices().ifPresent(HostDesktop.Notices::closeStopQuestion);
                    windows.stop();
                }
            });
            // Off the registry's thread: the desktop may take a while (KWin's is a D-Bus round trip). The cursor
            // matters in mouse mode only.
            registry.setFocusRequestHandler((client, pointer) -> Thread.ofVirtual().start(() -> {
                windows.refocus(pointer);
                windows.hideIdleCursor(!pointer && config.stream().hideIdleCursor());
            }));
        });
        desktop.screenLock().ifPresent(lock ->
                director.setScreenUnlocker(new ScreenUnlock(lock, () -> config.stream().unlockScreen())));
        if (desktop.windows().isPresent() || desktop.notices().isPresent()) {
            registry.setRemoteActionHandler(this::remoteAction);
        }
        registry.setStopCheckHandler(this::stopCheck);
        // On: wait for streaming clients to reconnect before turning anything off. Off: turn back on at once what an
        // earlier run left off (if it was killed before it could).
        boolean turnOff = config.monitor().turnOffWhenIdle();
        director.setTurnOffWhenIdle(turnOff, turnOff ? STARTUP_GRACE : Duration.ZERO);
        director.setMatchResolution(config.monitor().matchResolution());
        registry.setPinHandler((client, pin, address) -> {
            SunshineClient s = sunshine;
            return s == null
                    ? CompletableFuture.failedFuture(new IllegalStateException("Sunshine login is not set up"))
                    : s.submitPin(pin, client.hostname(), address);
        });
    }

    /**
     * What a stop of the client's stream would lose; for its remote, the question goes on Sunshine's monitor where the
     * desktop can show it.
     */
    private Message.StopCheck stopCheck(ClientView client, boolean remote) throws IOException, InterruptedException {
        Optional<String> quits = stopQuits(client);
        if (quits.isEmpty()) {
            return new Message.StopCheck(null, false);
        }
        boolean shown = false;
        Optional<HostDesktop.Notices> notices = desktop.notices();
        if (remote && notices.isPresent()) {
            Optional<String> output = director.sunshineMonitor();
            shown = output.isPresent() && notices.get().askToStop(output.get(), quits.get());
        }
        LOG.info(() -> "stopping " + client.hostname() + "'s stream would quit "
                + (quits.get().isEmpty() ? "the app" : quits.get()) + "; asking first");
        return new Message.StopCheck(quits.get(), shown);
    }

    /**
     * The app a stop of the client's stream would quit, losing anything unsaved in it: empty when nothing would be
     * lost, i.e. others still stream it or it doesn't start anything (Desktop); an empty name when a stream not
     * started from Orbit runs an unknown app. Asks Sunshine, so not on the event thread.
     */
    Optional<String> stopQuits(ClientView client) {
        if (!client.streaming() || !director.othersStreaming(client.agentId()).isEmpty()) {
            return Optional.empty();
        }
        String app = client.currentApp();
        if (app == null) {
            return Optional.of("");
        }
        // Assumed to start something when the app list can't be read.
        return sunshineApp(app).map(SunshineClient.App::launches).orElse(true) ? Optional.of(app) : Optional.empty();
    }

    /** What a client's TV remote did during a stream: shown on the streamed monitor; the cursor follows its mode. */
    private void remoteAction(ClientView client, String action) {
        Optional<HostDesktop.Notices> question = desktop.notices();
        switch (action == null ? "" : action) {
            // Answers to the stop question shown for this client: whatever the notices setting.
            case Message.RemoteAction.STOP_QUESTION_OK -> question.ifPresent(n -> n.selectStop(true));
            case Message.RemoteAction.STOP_QUESTION_CANCEL -> question.ifPresent(n -> n.selectStop(false));
            case Message.RemoteAction.STOP_QUESTION_CLOSED -> question.ifPresent(HostDesktop.Notices::closeStopQuestion);
            default -> showRemoteAction(action);
        }
    }

    private void showRemoteAction(String action) {
        Thread.ofVirtual().start(() -> {
            HostConfig.Stream settings = config.stream();
            desktop.windows().ifPresent(windows -> {
                if (Message.RemoteAction.KEY_MODE.equals(action)) {
                    windows.hideIdleCursor(settings.hideIdleCursor());
                } else if (Message.RemoteAction.MOUSE_MODE.equals(action)) {
                    windows.hideIdleCursor(false);
                }
            });
            if (settings.remoteNotices()) {
                desktop.notices().ifPresent(notices -> notices.remoteAction(action));
            }
        });
    }

    /** What the desktop integration offers, for the log. */
    private static String describeParts(HostDesktop desktop) {
        List<String> parts = new ArrayList<>();
        desktop.display().ifPresent(d -> parts.add("monitor modes"));
        desktop.windows().ifPresent(w -> parts.add("window placement"));
        desktop.notices().ifPresent(n -> parts.add("notices"));
        desktop.colors().ifPresent(c -> parts.add("colour scheme"));
        desktop.screenLock().ifPresent(l -> parts.add("screen lock"));
        return parts.isEmpty() ? "; streams work without matching monitor modes or placing windows"
                : " (" + String.join(", ", parts) + ")";
    }

    public static void main(String[] args) throws Exception {
        ConsoleLog.install();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) ->
                LOG.log(Level.SEVERE, "uncaught error on thread " + thread.getName(), e));
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Orbit host needs a desktop session.");
            System.exit(1);
        }
        Path configDir = args.length > 0 ? Path.of(args[0]) : JsonFiles.configDir();
        OrbitHost host = new OrbitHost(configDir);
        host.start();
        Runtime.getRuntime().addShutdownHook(new Thread(host::stop));
        // Asked before the event thread starts, so the first window already has the right theme.
        boolean systemDark = SystemTheme.prefersDark().orElse(false);
        Optional<DesktopColors.Source> colors = host.desktop.colors();
        DesktopColors scheme = colors.flatMap(DesktopColors.Source::read).orElse(null);
        SwingUtilities.invokeLater(() -> {
            host.themes = new ThemeSwitcher(ThemeSwitcher.Choice.parse(host.config().theme()), systemDark,
                    colors.orElse(null), scheme);
            host.themes.install();
            MainWindow window = new MainWindow(host);
            window.open(!TrayController.install(host, window));
        });
    }

    void start() throws IOException {
        int port = server.start(config.port());
        String name = config.advertiseName() != null ? config.advertiseName()
                : InetAddress.getLocalHost().getHostName();
        mdns.start(name, port);
        // A periodic task that throws is never run again, so a bug in one check mustn't end the watch.
        sunshineWatch.scheduleWithFixedDelay(() -> {
            try {
                checkSunshine();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "checking Sunshine failed", e);
            }
        }, 0, SUNSHINE_WATCH.toSeconds(), TimeUnit.SECONDS);
    }

    private void checkSunshine() {
        boolean running;
        try {
            SunshineClient.ensureRunning(config.sunshineUri());
            running = true;
        } catch (IOException e) {
            running = false;
        }
        registry.setSunshineRunning(running);
        SunshineClient s = sunshine;
        // Every check (5 s), unlike the pairings: Moonlight shows its PIN only while it waits.
        if (running && s != null && !noPendingPairings) {
            readPendingPairings(s);
        } else {
            registry.setPendingPairings(List.of());
        }
        int every = director.streamingClients().isEmpty() ? PAIRED_EVERY : PAIRED_EVERY_WHILE_STREAMING;
        if (running && s != null && checks++ % every == 0) {
            try {
                List<SunshineClient.PairedClient> pairings = s.listClients();
                registry.setSunshinePairings(pairings);
                Set<String> paired = pairings.stream().map(SunshineClient.PairedClient::name)
                        .collect(Collectors.toSet());
                if (lastPaired != null) {
                    director.disconnectUnpaired(lastPaired, paired);
                }
                lastPaired = paired;
                readMaxStreams();
            } catch (IOException e) {
                LOG.log(Level.FINE, "cannot list Sunshine's paired clients", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    int maxStreams() {
        return maxStreams;
    }

    /** Reads Sunshine's limit now (before each start, and every 30 s). Unlimited without a Sunshine login. */
    private int readMaxStreams() throws IOException, InterruptedException {
        SunshineClient s = sunshine;
        int max = s == null ? Integer.MAX_VALUE : s.maxStreams();
        if (max != maxStreams) {
            maxStreams = max;
            LOG.info(() -> "Sunshine streams to " + max + (max == 1 ? " client" : " clients") + " at a time");
        }
        return max;
    }

    void stop() {
        sunshineWatch.shutdownNow();
        registry.saveOnShutdown();
        director.close();
        splash.close();
        notice.close();
        desktop.close();
        mdns.close();
        server.close();
    }

    /**
     * Sunshine's {@code app}, for whether it starts anything and its cover; empty when the app list can't be read.
     * Desktop starts nothing, so there's no app window to find, and the first window to get focus would be whatever
     * someone clicks at the PC.
     */
    private Optional<SunshineClient.App> sunshineApp(String app) {
        SunshineClient s = sunshine;
        if (s == null) {
            return Optional.empty();
        }
        try {
            return s.listApps().stream().filter(a -> a.name().equals(app)).findFirst();
        } catch (IOException e) {
            LOG.log(Level.FINE, "cannot read Sunshine's apps", e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    AgentRegistry registry() {
        return registry;
    }

    StreamDirector director() {
        return director;
    }

    HostConfig config() {
        return config;
    }

    MonitorMemory monitorMemory() {
        return monitorMemory;
    }

    ThemeSwitcher themes() {
        return themes;
    }

    /** Sunshine client, or null until credentials are configured. */
    SunshineClient sunshine() {
        return sunshine;
    }

    /** The Sunshine client, or why there's none: its login isn't set up. */
    private SunshineClient requireSunshine() throws IOException {
        SunshineClient s = sunshine;
        if (s == null) {
            throw new IOException("the Sunshine login isn't set up in Orbit");
        }
        return s;
    }

    /** What the desktop integration offers, for which settings apply here. */
    HostDesktop desktop() {
        return desktop;
    }

    /**
     * Saves {@code updated} and applies what changed, except the port and mDNS name, which take a restart. Event
     * thread only.
     */
    void applySettings(HostConfig updated) throws IOException {
        HostConfig old = config;
        updated.save(configFile);
        config = updated;
        if (!updated.sunshine().equals(old.sunshine())) {
            sunshine = newSunshineClient(updated);
            noPendingPairings = false;
            LOG.info(() -> "Sunshine settings updated (" + updated.sunshine().url() + ")");
        }
        boolean turnOff = updated.monitor().turnOffWhenIdle();
        if (turnOff != old.monitor().turnOffWhenIdle()) {
            director.setTurnOffWhenIdle(turnOff, Duration.ZERO);
            LOG.info(() -> (turnOff ? "turning" : "no longer turning") + " Sunshine's monitor off while nothing streams");
        }
        director.setMatchResolution(updated.monitor().matchResolution());
        if (!updated.stream().hideIdleCursor()) {
            desktop.windows().ifPresent(windows -> Thread.ofVirtual().start(() -> windows.hideIdleCursor(false)));
        }
        ThemeSwitcher.Choice theme = ThemeSwitcher.Choice.parse(updated.theme());
        if (themes != null && theme != themes.choice()) {
            themes.choose(theme);
        }
        if (!updated.equals(old)) {
            LOG.info("settings saved");
        }
    }

    /**
     * Removes every Sunshine pairing under {@code hostname} (re-pairing can leave several), and updates the clients'
     * view of Sunshine's list right away. Blocking; not on the event thread.
     *
     * @return how many pairings were removed
     */
    int unpairInSunshine(String hostname) throws IOException, InterruptedException {
        SunshineClient s = requireSunshine();
        List<SunshineClient.PairedClient> matching = s.listClients().stream()
                .filter(c -> c.name().equalsIgnoreCase(hostname)).toList();
        for (SunshineClient.PairedClient c : matching) {
            s.unpair(c.uuid());
        }
        LOG.info(() -> "unpaired " + hostname + " in Sunshine (" + matching.size() + " pairing"
                + (matching.size() == 1 ? ")" : "s)"));
        refreshSunshinePairings();
        return matching.size();
    }

    /**
     * Removes one Sunshine pairing, e.g. of a client without an Orbit agent, and updates the clients' view of
     * Sunshine's list right away. Blocking; not on the event thread.
     */
    void unpairInSunshine(SunshineClient.PairedClient pairing) throws IOException, InterruptedException {
        SunshineClient s = requireSunshine();
        s.unpair(pairing.uuid());
        LOG.info(() -> "unpaired " + pairing.name() + " (" + pairing.uuid() + ") in Sunshine");
        refreshSunshinePairings();
    }

    /**
     * Pairs a Moonlight client without an Orbit agent that waits in Sunshine, with the PIN it shows, and updates the
     * lists right away. Blocking; not on the event thread.
     *
     * @return its new pairing, or empty if Sunshine's list doesn't show which one it is
     */
    Optional<SunshineClient.PairedClient> pairInSunshine(SunshineClient.PendingPairing request, String pin,
            String name) throws IOException, InterruptedException {
        SunshineClient s = requireSunshine();
        Set<String> before = s.listClients().stream().map(SunshineClient.PairedClient::uuid)
                .collect(Collectors.toSet());
        s.pair(request, pin, name);
        LOG.info(() -> "paired " + name + " (" + request.address() + ") in Sunshine");
        List<SunshineClient.PairedClient> after = s.listClients();
        // The request first: the window would otherwise show both rows for a moment.
        readPendingPairings(s);
        registry.setSunshinePairings(after);
        return after.stream().filter(p -> !before.contains(p.uuid())).findFirst();
    }

    private void readPendingPairings(SunshineClient s) {
        try {
            Optional<List<SunshineClient.PendingPairing>> pending = s.pendingPairings();
            if (pending.isEmpty()) {
                noPendingPairings = true;
                LOG.info("this Sunshine doesn't list waiting pairing requests; pair Moonlight clients without an"
                        + " agent in its web UI");
            }
            registry.setPendingPairings(pending.orElse(List.of()));
        } catch (IOException e) {
            LOG.log(Level.FINE, "cannot list Sunshine's waiting pairing requests", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reads Sunshine's paired clients now instead of at the next periodic check, e.g. right after a pairing, so the
     * client's Pair button turns into Unpair at once. Blocking; not on the event thread.
     */
    void refreshSunshinePairings() throws IOException, InterruptedException {
        SunshineClient s = sunshine;
        if (s != null) {
            registry.setSunshinePairings(s.listClients());
        }
    }

    private static SunshineClient newSunshineClient(HostConfig config) {
        return config.hasSunshineCredentials()
                ? new SunshineClient(config.sunshineUri(), config.sunshine().user(), config.sunshine().password())
                : null;
    }
}
