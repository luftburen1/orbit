package orbit.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import orbit.protocol.ClientState;
import orbit.protocol.JsonFiles;
import orbit.protocol.Message;
import orbit.protocol.Protocol;
import orbit.protocol.StreamSettings;

/**
 * All clients the host knows about, their connections and in-flight commands.
 *
 * <p>Thread-safe: every mutation holds the registry lock, and listeners are called after it is released.
 */
public final class AgentRegistry {

    private static final Logger LOG = Logger.getLogger(AgentRegistry.class.getName());

    static final Duration STREAM_TIMEOUT = Duration.ofSeconds(30);
    /**
     * Agents answer a start once Moonlight's connection is up (older agents after 4 s). After checking pairing and
     * waking the TV, they give Moonlight 45 s to find the host and the app, and then 135 s more for the app's launch,
     * whose prep commands may take long, and the connection.
     */
    static final Duration START_TIMEOUT = Duration.ofMinutes(4);
    static final Duration PAIR_TIMEOUT = Duration.ofSeconds(120);

    /** The host's side of one agent connection. */
    public interface AgentLink {
        void send(Message.FromHost message);

        /** The agent's IP address as this host sees it, or null if unknown. */
        default String remoteAddress() {
            return null;
        }

        /** Sends {@code goodbye} if non-null, then closes the connection. */
        void close(Message.FromHost goodbye);
    }

    public interface Listener {
        void clientChanged(ClientView client);

        void clientRemoved(String agentId);

        /**
         * Sunshine's pairings or waiting pairing requests, or whether it runs, changed, so {@link #agentlessPairings}
         * or {@link #pairingRequests} may have.
         */
        default void pairingsChanged() {
        }
    }

    /** Starts the stream a client asked for, e.g. with its TV remote, as if Start were clicked. */
    public interface StreamRequestHandler {
        void streamRequested(ClientView client, String app);
    }

    /** Stops the stream a client asked to stop, e.g. with its TV remote, as if Stop were clicked. */
    public interface StopRequestHandler {
        void stopRequested(ClientView client);
    }

    /** Tells a client whether stopping its stream would lose anything, so it asks whoever is there first. */
    public interface StopCheckHandler {
        /**
         * Called off the connection's thread: it may ask Sunshine.
         *
         * @param remote asked with the TV remote: show the question on the streamed monitor if possible
         */
        Message.StopCheck stopCheck(ClientView client, boolean remote) throws IOException, InterruptedException;
    }

    /** Gives focus back to the stream for a client's TV remote, which is about to send keys or move the mouse. */
    public interface FocusRequestHandler {
        /** @param pointer the cursor should be on the stream too */
        void focusRequested(ClientView client, boolean pointer);
    }

    /** Shows what a client's TV remote did on the streamed monitor, e.g. "Mouse mode". */
    public interface RemoteActionHandler {
        /** @param action one of the {@link Message.RemoteAction} constants */
        void remoteAction(ClientView client, String action);
    }

    /** Sunshine's apps, for a client to pick from. */
    public interface AppSource {
        List<SunshineClient.App> apps() throws IOException, InterruptedException;
    }

    /** The app a client streams when it asks for a stream and has never streamed one from Orbit. */
    static final String DEFAULT_APP = "Desktop";

    /** Submits a PIN reported by an agent's {@code moonlight pair} to Sunshine. */
    public interface PinHandler {
        /** @param address the client's IP address as this host sees it, or null; picks its pairing request */
        CompletionStage<Void> submitPin(ClientView client, String pin, String address);
    }

    /**
     * Persisted form of an approved client in {@code clients.json}.
     *
     * @param lastSeen ISO-8601 instant, or null if never seen; absent in files from older versions
     * @param moonlightVersion last reported, so offline clients still show it; may be null
     * @param agentVersion last reported; may be null
     * @param lastApp the app it last streamed from Orbit, started when it asks for a stream; may be null
     * @param hardwareDecoders last reported codecs it decodes in hardware; null if unknown
     */
    record StoredClient(String agentId, String hostname, String tokenHash, StreamSettings settings, String lastSeen,
            String moonlightVersion, String agentVersion, String lastApp, List<String> hardwareDecoders) {
    }

    record Store(List<StoredClient> clients) {
    }

    private final class Entry {
        final String agentId;
        String hostname;
        String tokenHash;
        StreamSettings settings = StreamSettings.DEFAULT;
        AgentLink link;
        ClientState state;
        String currentApp;
        Instant lastSeen;
        String moonlightVersion;
        String agentVersion;
        boolean moonlightMissing;
        String problem;
        boolean streamsBlocked;
        boolean remote;
        /** Orbit is pairing it: Sunshine's waiting request from its address is this pairing's. */
        boolean pairing;
        String lastApp;
        List<String> hardwareDecoders;

        Entry(String agentId) {
            this.agentId = agentId;
        }

        boolean approved() {
            return tokenHash != null;
        }

        ClientView view() {
            return new ClientView(agentId, hostname,
                    approved() ? ClientView.Approval.APPROVED : ClientView.Approval.PENDING,
                    link != null, link != null ? state : null, link != null ? currentApp : null,
                    settings, lastSeen, moonlightVersion, agentVersion, link != null && moonlightMissing, sunshineDown,
                    link != null ? problem : null, link != null && streamsBlocked, sunshinePaired, hardwareDecoders,
                    link != null && remote, disabledIn(hostname), null, null);
        }
    }

    private record InFlight(String agentId, CompletableFuture<Message.CommandResult> result) {
    }

    private final Object lock = new Object();
    /** Sunshine doesn't answer on this machine, so no client can pair or stream; shown on every client. */
    private volatile boolean sunshineDown;
    /** Names Sunshine has paired clients under, sorted; null until known (needs the Sunshine login). */
    private volatile List<String> sunshinePaired;
    /** Sunshine's pairings, sorted by name; null until known. Set together with {@link #sunshinePaired}. */
    private volatile List<SunshineClient.PairedClient> sunshinePairings;
    /** Pairing requests waiting in Sunshine for their PIN; empty if none or unknown. */
    private volatile List<SunshineClient.PendingPairing> pendingPairings = List.of();
    /** Names whose every Sunshine pairing is disabled there. Set together with {@link #sunshinePaired}. */
    private volatile List<String> sunshineDisabled = List.of();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, InFlight> inFlight = new LinkedHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final SecureRandom random = new SecureRandom();
    private final Path storeFile;
    private final Clock clock;
    private volatile StreamRequestHandler streamRequestHandler = (client, app) ->
            refuseStreamRequest(client.agentId(), "the Orbit host isn't ready yet");
    private volatile StopRequestHandler stopRequestHandler = client -> stopStream(client.agentId());
    private volatile StopCheckHandler stopCheckHandler = (client, remote) -> new Message.StopCheck(null, false);
    /** Null where focus can't be moved; then {@link Message.Welcome} doesn't offer it. */
    private volatile FocusRequestHandler focusRequestHandler;
    /** Null where nothing can be shown; then {@link Message.Welcome} doesn't offer it. */
    private volatile RemoteActionHandler remoteActionHandler;
    private volatile AppSource appSource = () -> {
        throw new IOException("the Sunshine login isn't set up in Orbit");
    };
    private volatile PinHandler pinHandler = (client, pin, address) ->
            CompletableFuture.failedFuture(new IllegalStateException("Sunshine is not configured"));

    /** @param storeFile where approved clients are saved, or null to keep them in memory only */
    public AgentRegistry(Path storeFile, Clock clock) {
        this.storeFile = storeFile;
        this.clock = clock;
    }

    public void load() throws IOException {
        if (storeFile == null) {
            return;
        }
        Store store = JsonFiles.read(storeFile, Store.class).orElse(new Store(List.of()));
        synchronized (lock) {
            for (StoredClient c : store.clients()) {
                Entry e = new Entry(c.agentId());
                e.hostname = c.hostname();
                e.tokenHash = c.tokenHash();
                e.settings = c.settings() != null ? c.settings() : StreamSettings.DEFAULT;
                e.lastSeen = parseInstant(c.lastSeen());
                e.moonlightVersion = c.moonlightVersion();
                e.agentVersion = c.agentVersion();
                e.lastApp = c.lastApp();
                e.hardwareDecoders = c.hardwareDecoders();
                entries.put(e.agentId, e);
            }
        }
    }

    /** Records whether Sunshine answers, and updates every client's view when that changes. */
    public void setSunshineRunning(boolean running) {
        if (sunshineDown == !running) {
            return;
        }
        sunshineDown = !running;
        LOG.info(running ? "Sunshine answers again" : "Sunshine isn't answering; clients can't pair or stream");
        List<AgentLink> links;
        synchronized (lock) {
            links = entries.values().stream().map(e -> e.link).filter(Objects::nonNull).toList();
        }
        links.forEach(l -> l.send(new Message.SunshineState(running)));
        clients().forEach(this::fireChanged);
        listeners.forEach(Listener::pairingsChanged);
    }

    /** Records Sunshine's pairings, and updates the views if they changed. */
    public void setSunshinePairings(Collection<SunshineClient.PairedClient> pairings) {
        List<SunshineClient.PairedClient> sorted = pairings == null ? null : pairings.stream()
                .sorted(Comparator.comparing(SunshineClient.PairedClient::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(SunshineClient.PairedClient::uuid))
                .toList();
        if (Objects.equals(sorted, sunshinePairings)) {
            return;
        }
        List<String> names = sorted == null ? null
                : sorted.stream().map(SunshineClient.PairedClient::name).distinct().toList();
        // Re-pairing can leave several pairings under one name; one enabled is enough to stream.
        List<String> disabled = names == null ? List.of() : names.stream()
                .filter(n -> sorted.stream().noneMatch(p -> p.enabled() && p.name().equals(n)))
                .toList();
        sunshinePairings = sorted;
        boolean namesChanged = !Objects.equals(names, sunshinePaired) || !disabled.equals(sunshineDisabled);
        List<ClientView> before = clients();
        sunshinePaired = names;
        sunshineDisabled = disabled;
        if (namesChanged) {
            // Moonlight takes a disabled client for unpaired: clients disabled or enabled just now check again, so
            // they don't wait for their next check (30 s) to say so.
            before.stream()
                    .filter(c -> c.online() && c.disabledInSunshine() != disabledIn(c.hostname()))
                    .forEach(c -> checkPairing(c.agentId()));
            clients().forEach(this::fireChanged);
        }
        listeners.forEach(Listener::pairingsChanged);
    }

    /** Records the pairing requests waiting in Sunshine, and updates the views if they changed. */
    public void setPendingPairings(List<SunshineClient.PendingPairing> pending) {
        if (pending.equals(pendingPairings)) {
            return;
        }
        pendingPairings = List.copyOf(pending);
        listeners.forEach(Listener::pairingsChanged);
    }

    /**
     * Pairing requests waiting in Sunshine from Moonlight clients without an Orbit agent: those from the address of an
     * agent Orbit is pairing are that pairing's, and get their PIN from the agent.
     */
    public List<ClientView> pairingRequests() {
        List<String> pairingAgents;
        synchronized (lock) {
            pairingAgents = entries.values().stream()
                    .filter(e -> e.pairing && e.link != null && e.link.remoteAddress() != null)
                    .map(e -> SunshineClient.plainAddress(e.link.remoteAddress()))
                    .toList();
        }
        return pendingPairings.stream()
                .filter(p -> !pairingAgents.contains(SunshineClient.plainAddress(p.address())))
                .map(p -> ClientView.pairingRequest(p, sunshineDown))
                .toList();
    }

    /** Whether Sunshine has {@code hostname} paired only with pairings disabled there. */
    private boolean disabledIn(String hostname) {
        return sunshineDisabled.stream().anyMatch(n -> n.equalsIgnoreCase(hostname));
    }

    /**
     * Sunshine's pairings that no approved client's hostname matches, which is how Orbit pairs clients: Moonlight
     * clients without an agent, or paired under another name. Empty while Sunshine's list is unknown.
     */
    public List<ClientView> agentlessPairings() {
        List<SunshineClient.PairedClient> pairings = sunshinePairings;
        if (pairings == null) {
            return List.of();
        }
        List<String> hostnames;
        synchronized (lock) {
            hostnames = entries.values().stream().filter(Entry::approved).map(e -> e.hostname).toList();
        }
        return pairings.stream()
                .filter(p -> hostnames.stream().noneMatch(p.name()::equalsIgnoreCase))
                .map(p -> ClientView.agentless(p, sunshineDown))
                .toList();
    }

    public void setPinHandler(PinHandler pinHandler) {
        this.pinHandler = pinHandler;
    }

    public void setStreamRequestHandler(StreamRequestHandler handler) {
        this.streamRequestHandler = handler;
    }

    public void setStopRequestHandler(StopRequestHandler handler) {
        this.stopRequestHandler = handler;
    }

    public void setStopCheckHandler(StopCheckHandler handler) {
        this.stopCheckHandler = handler;
    }

    /** Set before agents connect: the welcome tells them whether to ask. */
    public void setFocusRequestHandler(FocusRequestHandler handler) {
        this.focusRequestHandler = handler;
    }

    /** Set before agents connect: the welcome tells them whether to send them. */
    public void setRemoteActionHandler(RemoteActionHandler handler) {
        this.remoteActionHandler = handler;
    }

    private Message.Welcome welcome(String token, StreamSettings settings) {
        return new Message.Welcome(token, settings, true, true, focusRequestHandler != null,
                remoteActionHandler != null, true);
    }

    public void setAppSource(AppSource source) {
        this.appSource = source;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public List<ClientView> clients() {
        synchronized (lock) {
            return entries.values().stream().map(Entry::view).toList();
        }
    }

    public Optional<ClientView> client(String agentId) {
        synchronized (lock) {
            return Optional.ofNullable(entries.get(agentId)).map(Entry::view);
        }
    }

    /**
     * The app another client streams, which is the only one {@code agentId} can join: Sunshine runs one app at a
     * time. Empty if no other client streams, or their streams weren't started from Orbit, so the app is unknown.
     */
    public Optional<ClientView> streamingOther(String agentId) {
        return clients().stream()
                .filter(c -> !c.agentId().equals(agentId) && c.approved() && c.streaming() && c.currentApp() != null)
                .findFirst();
    }

    // ---- connection lifecycle (called by the server) ----

    /**
     * Handles the first message of a connection. Returns true if the connection is accepted (approved or pending)
     * and later messages from it should be passed to {@link #received}.
     */
    public boolean connected(Message.Hello hello, AgentLink link) {
        if (hello.protocolVersion() != Protocol.VERSION) {
            link.close(new Message.Rejected("protocol version " + hello.protocolVersion()
                    + " is not supported; host speaks " + Protocol.VERSION));
            return false;
        }
        if (hello.agentId() == null || hello.agentId().isBlank()) {
            link.close(new Message.Rejected("missing agent id"));
            return false;
        }
        AgentLink replaced;
        ClientView view;
        synchronized (lock) {
            Entry e = entries.get(hello.agentId());
            if (e != null && e.approved() && !tokenMatches(e, hello.token())) {
                link.close(new Message.Rejected("invalid token; remove this client on the host to pair it again"));
                return false;
            }
            if (e == null) {
                e = new Entry(hello.agentId());
                entries.put(e.agentId, e);
            }
            replaced = e.link;
            e.link = link;
            e.hostname = hello.hostname();
            e.moonlightVersion = hello.moonlightVersion();
            e.agentVersion = hello.agentVersion();
            e.hardwareDecoders = hello.hardwareDecoders();
            e.moonlightMissing = false;
            e.remote = false;
            e.lastSeen = clock.instant();
            e.state = null;
            e.currentApp = null;
            // Agents assume Sunshine runs; told first if it doesn't, so they don't mistake that for "not paired".
            if (sunshineDown) {
                link.send(new Message.SunshineState(false));
            }
            link.send(e.approved() ? welcome(null, e.settings) : new Message.Pending());
            if (e.approved()) {
                saveLocked();
            }
            view = e.view();
        }
        if (replaced != null) {
            replaced.close(new Message.Rejected("replaced by a newer connection"));
        }
        LOG.info(() -> "agent " + hello.hostname() + " (" + hello.agentId() + ") connected"
                + (view.approved() ? "" : ", awaiting approval"));
        fireChanged(view);
        return true;
    }

    public void received(String agentId, AgentLink link, Message.FromAgent message) {
        switch (message) {
            case Message.Status s -> update(agentId, link, e -> {
                e.state = s.state();
                e.currentApp = s.currentApp();
                e.moonlightMissing = s.moonlightMissing();
                e.problem = s.problem();
                e.streamsBlocked = s.streamsBlocked();
                e.remote = s.remote();
                if (s.moonlightVersion() != null) {
                    e.moonlightVersion = s.moonlightVersion();
                }
                // Only streams Orbit started have an app; that's what a stream request starts again.
                if (s.state() == ClientState.STREAMING && s.currentApp() != null
                        && !s.currentApp().equals(e.lastApp)) {
                    e.lastApp = s.currentApp();
                    saveLocked();
                }
            });
            case Message.CommandResult r -> complete(agentId, r);
            case Message.PairPin p -> onPairPin(agentId, link, p);
            case Message.StreamRequest r -> onStreamRequest(agentId, link, r.app());
            case Message.AppListRequest r -> onAppListRequest(agentId, link);
            case Message.StopRequest r -> approvedView(agentId, link).ifPresent(view -> {
                LOG.info(() -> view.hostname() + " asks to stop its stream");
                stopRequestHandler.stopRequested(view);
            });
            case Message.StopCheckRequest r -> onStopCheckRequest(agentId, link, r.remote());
            case Message.FocusRequest r -> {
                FocusRequestHandler handler = focusRequestHandler;
                streamingView(agentId, link, handler).ifPresent(view -> handler.focusRequested(view, r.pointer()));
            }
            case Message.RemoteAction a -> {
                RemoteActionHandler handler = remoteActionHandler;
                streamingView(agentId, link, handler).ifPresent(view -> handler.remoteAction(view, a.action()));
            }
            case Message.Hello h -> LOG.warning(() -> "ignoring repeated hello from " + agentId);
        }
    }

    /** @param chosen the app picked on the client, or null for its last one */
    private void onStreamRequest(String agentId, AgentLink link, String chosen) {
        Optional<ClientView> view = approvedView(agentId, link);
        if (view.isEmpty()) {
            return;
        }
        // Without a choice, the app another client streams, since that's the only one it can join.
        String app = chosen != null && !chosen.isBlank() ? chosen
                : streamingOther(agentId).map(ClientView::currentApp).orElseGet(() -> lastApp(agentId));
        LOG.info(() -> view.get().hostname() + " asks for a stream of " + app);
        streamRequestHandler.streamRequested(view.get(), app);
    }

    /** Reads Sunshine's apps off the connection's thread, since that asks Sunshine, and sends them to the client. */
    private void onAppListRequest(String agentId, AgentLink link) {
        if (approvedView(agentId, link).isEmpty()) {
            return;
        }
        // While another client streams, its app is the only one this client can join, so it's the only one offered.
        String running = streamingOther(agentId).map(ClientView::currentApp).orElse(null);
        String lastApp = running != null ? running : lastApp(agentId);
        Thread.ofVirtual().name("orbit-app-list").start(() -> {
            Message.AppList answer;
            try {
                List<SunshineClient.App> apps = appSource.apps().stream()
                        .filter(app -> running == null || app.name().equals(running)).toList();
                answer = new Message.AppList(apps.stream().map(SunshineClient.App::name).toList(), lastApp, null,
                        apps.stream().map(app -> AppIcons.icon(app.cover())).toList());
            } catch (IOException e) {
                answer = new Message.AppList(List.of(), lastApp, "can't read Sunshine's apps: " + e.getMessage());
            } catch (InterruptedException e) {
                return;
            }
            Message.AppList sent = answer;
            synchronized (lock) {
                Entry e = entries.get(agentId);
                if (e != null && e.link == link) {
                    link.send(sent);
                }
            }
        });
    }

    /**
     * Answers off the connection's thread, since that may ask Sunshine. A failed check answers that nothing would be
     * lost: the client asked to stop, and a question it can't get rid of would be worse.
     */
    private void onStopCheckRequest(String agentId, AgentLink link, boolean remote) {
        Optional<ClientView> view = approvedView(agentId, link);
        if (view.isEmpty()) {
            return;
        }
        Thread.ofVirtual().name("orbit-stop-check").start(() -> {
            Message.StopCheck answer;
            try {
                answer = stopCheckHandler.stopCheck(view.get(), remote);
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "cannot tell what stopping " + view.get().hostname() + "'s stream loses", e);
                answer = new Message.StopCheck(null, false);
            } catch (InterruptedException e) {
                return;
            }
            Message.StopCheck sent = answer;
            synchronized (lock) {
                Entry e = entries.get(agentId);
                if (e != null && e.link == link) {
                    link.send(sent);
                }
            }
        });
    }

    /** The client, if it's approved and streaming and {@code handler} is set: what its TV remote does acts on that. */
    private Optional<ClientView> streamingView(String agentId, AgentLink link, Object handler) {
        return handler == null ? Optional.empty() : approvedView(agentId, link).filter(ClientView::streaming);
    }

    /** The client's view if {@code link} is its current connection and it's approved. */
    private Optional<ClientView> approvedView(String agentId, AgentLink link) {
        synchronized (lock) {
            Entry e = entries.get(agentId);
            return e == null || e.link != link || !e.approved() ? Optional.empty() : Optional.of(e.view());
        }
    }

    /** The app the client streamed last from Orbit, or {@link #DEFAULT_APP}. */
    public String lastApp(String agentId) {
        synchronized (lock) {
            Entry e = entries.get(agentId);
            return e != null && e.lastApp != null ? e.lastApp : DEFAULT_APP;
        }
    }

    /** Tells a client why the stream it asked for didn't start. */
    public void refuseStreamRequest(String agentId, String reason) {
        LOG.info(() -> "stream request from " + agentId + " refused: " + reason);
        synchronized (lock) {
            Entry e = entries.get(agentId);
            if (e != null && e.link != null) {
                e.link.send(new Message.StreamRequestRefused(reason));
            }
        }
    }

    public void disconnected(String agentId, AgentLink link) {
        List<CompletableFuture<Message.CommandResult>> orphaned = new ArrayList<>();
        ClientView view;
        boolean removed = false;
        synchronized (lock) {
            Entry e = entries.get(agentId);
            if (e == null || e.link != link) {
                return;
            }
            e.link = null;
            e.lastSeen = clock.instant();
            if (e.approved()) {
                saveLocked();
            }
            inFlight.values().removeIf(f -> {
                if (f.agentId().equals(agentId)) {
                    orphaned.add(f.result());
                    return true;
                }
                return false;
            });
            if (!e.approved()) {
                entries.remove(agentId);
                removed = true;
            }
            view = e.view();
        }
        orphaned.forEach(f -> f.completeExceptionally(new IllegalStateException("client disconnected")));
        LOG.info(() -> "agent " + view.hostname() + " disconnected");
        if (removed) {
            fireRemoved(agentId);
        } else {
            fireChanged(view);
        }
    }

    // ---- actions (called by the UI) ----

    /** Approves a pending client, issuing it a token. */
    public void approve(String agentId) {
        String token = newToken();
        ClientView view;
        synchronized (lock) {
            Entry e = require(agentId);
            if (e.approved()) {
                return;
            }
            e.tokenHash = hash(token);
            if (e.link != null) {
                e.link.send(welcome(token, e.settings));
            }
            view = e.view();
            saveLocked();
        }
        fireChanged(view);
    }

    /** Forgets a client; its agent is disconnected and must be approved again. */
    public void remove(String agentId) {
        AgentLink link;
        synchronized (lock) {
            Entry e = entries.remove(agentId);
            if (e == null) {
                return;
            }
            link = e.link;
            saveLocked();
        }
        if (link != null) {
            link.close(new Message.Rejected("removed on the host"));
        }
        fireRemoved(agentId);
    }

    /** Asks a connected client to check its pairing now, e.g. right after unpairing it in Sunshine. */
    public void checkPairing(String agentId) {
        synchronized (lock) {
            Entry e = entries.get(agentId);
            if (e != null && e.link != null && e.approved()) {
                e.link.send(new Message.CheckPairing());
            }
        }
    }

    public void updateSettings(String agentId, StreamSettings settings) {
        ClientView view = update(agentId, null, e -> {
            e.settings = settings;
            if (e.link != null && e.approved()) {
                e.link.send(new Message.ApplySettings(settings));
            }
            saveLocked();
        });
        if (view == null) {
            throw new IllegalArgumentException("unknown client " + agentId);
        }
    }

    public CompletableFuture<Message.CommandResult> startStream(String agentId, String app) {
        return startStream(agentId, app, false);
    }

    /** @param quitAppOnFailure see {@link Message.StartStream#quitAppOnFailure} */
    public CompletableFuture<Message.CommandResult> startStream(String agentId, String app, boolean quitAppOnFailure) {
        return command(agentId, id -> new Message.StartStream(id, app, settingsOf(agentId), quitAppOnFailure),
                START_TIMEOUT);
    }

    public CompletableFuture<Message.CommandResult> stopStream(String agentId) {
        return command(agentId, Message.StopStream::new, STREAM_TIMEOUT);
    }

    /** Closes the client's stream but leaves the app running on the host, so the client can reconnect to it. */
    public CompletableFuture<Message.CommandResult> disconnectStream(String agentId) {
        return command(agentId, id -> new Message.StopStream(id, true), STREAM_TIMEOUT);
    }

    public CompletableFuture<Message.CommandResult> pair(String agentId) {
        synchronized (lock) {
            Optional.ofNullable(entries.get(agentId)).ifPresent(e -> e.pairing = true);
        }
        return command(agentId, Message.BeginPairing::new, PAIR_TIMEOUT).whenComplete((r, err) -> {
            synchronized (lock) {
                Optional.ofNullable(entries.get(agentId)).ifPresent(e -> e.pairing = false);
            }
            listeners.forEach(Listener::pairingsChanged);
        });
    }

    // ---- internals ----

    private CompletableFuture<Message.CommandResult> command(
            String agentId, Function<String, Message.Command> factory, Duration timeout) {
        String cmdId = UUID.randomUUID().toString();
        CompletableFuture<Message.CommandResult> result = new CompletableFuture<>();
        synchronized (lock) {
            Entry e = require(agentId);
            if (e.link == null) {
                return CompletableFuture.failedFuture(new IllegalStateException(e.hostname + " is offline"));
            }
            if (!e.approved()) {
                return CompletableFuture.failedFuture(new IllegalStateException(e.hostname + " is not approved"));
            }
            inFlight.put(cmdId, new InFlight(agentId, result));
            e.link.send(factory.apply(cmdId));
        }
        result.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((r, err) -> {
                    synchronized (lock) {
                        inFlight.remove(cmdId);
                    }
                });
        return result;
    }

    private void complete(String agentId, Message.CommandResult result) {
        InFlight f;
        synchronized (lock) {
            f = inFlight.get(result.cmdId());
            if (f == null || !f.agentId().equals(agentId)) {
                LOG.fine(() -> "unexpected result for command " + result.cmdId());
                return;
            }
            inFlight.remove(result.cmdId());
        }
        f.result().complete(result);
    }

    private void onPairPin(String agentId, AgentLink link, Message.PairPin pin) {
        ClientView view;
        synchronized (lock) {
            Entry e = entries.get(agentId);
            InFlight f = inFlight.get(pin.cmdId());
            if (e == null || e.link != link || !e.approved() || f == null || !f.agentId().equals(agentId)) {
                LOG.warning(() -> "ignoring unsolicited pairing PIN from " + agentId);
                return;
            }
            view = e.view();
        }
        pinHandler.submitPin(view, pin.pin(), link.remoteAddress()).whenComplete((ok, err) -> {
            // Tells the agent either way, so it can close Moonlight's pairing window instead of waiting it out.
            link.send(new Message.PairingDone(pin.cmdId(), err == null));
            if (err != null) {
                LOG.log(Level.WARNING, "submitting PIN for " + view.hostname() + " to Sunshine failed", err);
                InFlight f;
                synchronized (lock) {
                    f = inFlight.remove(pin.cmdId());
                }
                if (f != null) {
                    f.result().complete(Message.CommandResult.failure(pin.cmdId(),
                            "Sunshine rejected the PIN: " + rootMessage(err)));
                }
            }
        });
    }

    private StreamSettings settingsOf(String agentId) {
        synchronized (lock) {
            return require(agentId).settings;
        }
    }

    /** Applies {@code change} to a connected entry (or any entry if {@code link} is null) and fires a change. */
    private ClientView update(String agentId, AgentLink link, Consumer<Entry> change) {
        ClientView view;
        synchronized (lock) {
            Entry e = entries.get(agentId);
            if (e == null || (link != null && e.link != link)) {
                return null;
            }
            change.accept(e);
            e.lastSeen = clock.instant();
            view = e.view();
        }
        fireChanged(view);
        return view;
    }

    private Entry require(String agentId) {
        Entry e = entries.get(agentId);
        if (e == null) {
            throw new IllegalArgumentException("unknown client " + agentId);
        }
        return e;
    }

    /** Saves the clients, with connected ones last seen now. Called when the host shuts down. */
    public void saveOnShutdown() {
        synchronized (lock) {
            Instant now = clock.instant();
            entries.values().stream().filter(e -> e.link != null).forEach(e -> e.lastSeen = now);
            saveLocked();
        }
    }

    private static Instant parseInstant(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            LOG.warning(() -> "ignoring unreadable last-seen time " + text);
            return null;
        }
    }

    private void saveLocked() {
        if (storeFile == null) {
            return;
        }
        List<StoredClient> approved = entries.values().stream()
                .filter(Entry::approved)
                .map(e -> new StoredClient(e.agentId, e.hostname, e.tokenHash, e.settings,
                        e.lastSeen != null ? e.lastSeen.toString() : null, e.moonlightVersion, e.agentVersion,
                        e.lastApp, e.hardwareDecoders))
                .toList();
        try {
            JsonFiles.write(storeFile, new Store(approved));
        } catch (IOException ex) {
            LOG.log(Level.SEVERE, "cannot save " + storeFile, ex);
        }
    }

    private void fireChanged(ClientView view) {
        listeners.forEach(l -> l.clientChanged(view));
    }

    private void fireRemoved(String agentId) {
        listeners.forEach(l -> l.clientRemoved(agentId));
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean tokenMatches(Entry e, String token) {
        return token != null && MessageDigest.isEqual(
                e.tokenHash.getBytes(StandardCharsets.US_ASCII), hash(token).getBytes(StandardCharsets.US_ASCII));
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static String rootMessage(Throwable t) {
        while (t.getCause() != null && (t instanceof CompletionException || t instanceof ExecutionException)) {
            t = t.getCause();
        }
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
