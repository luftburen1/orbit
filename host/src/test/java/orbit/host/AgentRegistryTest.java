package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import orbit.protocol.ClientState;
import orbit.protocol.Message;
import orbit.protocol.Protocol;
import orbit.protocol.StreamSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentRegistryTest {

    @TempDir
    Path dir;

    static final class FakeLink implements AgentRegistry.AgentLink {
        final List<Message.FromHost> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        boolean closed;
        String address;

        @Override
        public String remoteAddress() {
            return address;
        }

        @Override
        public void send(Message.FromHost message) {
            sent.add(message);
        }

        @Override
        public void close(Message.FromHost goodbye) {
            if (goodbye != null) {
                sent.add(goodbye);
            }
            closed = true;
        }

        Message.FromHost last() {
            return sent.getLast();
        }
    }

    private AgentRegistry registry() {
        return new AgentRegistry(dir.resolve("clients.json"), Clock.systemUTC());
    }

    private static Message.Hello hello(String token) {
        return new Message.Hello(Protocol.VERSION, "agent-1", "livingroom", token, "6.1.0");
    }

    /** Connects a new agent, approves it and returns the issued token. */
    private static String approveNew(AgentRegistry registry, FakeLink link) {
        assertTrue(registry.connected(hello(null), link));
        registry.approve("agent-1");
        return ((Message.Welcome) link.last()).token();
    }

    @Test
    void newAgentIsPendingUntilApproved() {
        AgentRegistry registry = registry();
        FakeLink link = new FakeLink();

        assertTrue(registry.connected(hello(null), link));
        assertInstanceOf(Message.Pending.class, link.last());
        assertFalse(registry.clients().getFirst().approved());

        registry.approve("agent-1");
        Message.Welcome welcome = assertInstanceOf(Message.Welcome.class, link.last());
        assertNotNull(welcome.token());
        assertTrue(registry.clients().getFirst().approved());
    }

    @Test
    void welcomeSaysTheHostTakesStreamRequests() {
        AgentRegistry registry = registry();
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        assertTrue(((Message.Welcome) link.last()).streamRequests());
        assertTrue(((Message.Welcome) link.last()).streamControl());
        assertTrue(((Message.Welcome) link.last()).stopChecks());
    }

    @Test
    void streamRequestStartsTheLastAppAndItSurvivesARestart() throws Exception {
        AgentRegistry first = registry();
        List<String> started = new ArrayList<>();
        first.setStreamRequestHandler((client, app) -> started.add(client.hostname() + ": " + app));
        FakeLink link = new FakeLink();
        approveNew(first, link);

        first.received("agent-1", link, new Message.StreamRequest());
        assertEquals(List.of("livingroom: " + AgentRegistry.DEFAULT_APP), started, "never streamed: Desktop");

        first.received("agent-1", link, new Message.Status(ClientState.STREAMING, "Steam Big Picture"));
        first.received("agent-1", link, new Message.Status(ClientState.AVAILABLE, null));
        first.received("agent-1", link, new Message.StreamRequest());
        assertEquals("livingroom: Steam Big Picture", started.getLast());
        first.disconnected("agent-1", link);

        AgentRegistry second = registry();
        second.load();
        second.setStreamRequestHandler((client, app) -> started.add("after restart: " + app));
        FakeLink again = new FakeLink();
        assertTrue(second.connected(hello(((Message.Welcome) link.sent.stream()
                .filter(m -> m instanceof Message.Welcome w && w.token() != null).findFirst().orElseThrow())
                .token()), again));
        second.received("agent-1", again, new Message.StreamRequest());
        assertEquals("after restart: Steam Big Picture", started.getLast());
    }

    @Test
    void streamRequestStartsTheAppPickedOnTheClient() {
        AgentRegistry registry = registry();
        List<String> started = new ArrayList<>();
        registry.setStreamRequestHandler((client, app) -> started.add(app));
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        registry.received("agent-1", link, new Message.StreamRequest("Kodi"));
        registry.received("agent-1", link, new Message.StreamRequest(" "));
        assertEquals(List.of("Kodi", AgentRegistry.DEFAULT_APP), started);
    }

    @Test
    void sendsSunshinesAppsWithTheLastOne() throws Exception {
        AgentRegistry registry = registry();
        registry.setAppSource(() -> List.of(new SunshineClient.App("Desktop", false, ""),
                new SunshineClient.App("Steam Big Picture", true, "")));
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        registry.received("agent-1", link, new Message.Status(ClientState.STREAMING, "Steam Big Picture"));
        registry.received("agent-1", link, new Message.AppListRequest());
        assertEquals(new Message.AppList(List.of("Desktop", "Steam Big Picture"), "Steam Big Picture", null,
                java.util.Arrays.asList(null, null)), awaitSent(link, Message.AppList.class), "no covers, no icons");
    }

    @Test
    void whileAnotherClientStreamsOnlyItsAppIsOffered() throws Exception {
        AgentRegistry registry = registry();
        registry.setAppSource(() -> List.of(new SunshineClient.App("Desktop", false, ""),
                new SunshineClient.App("Steam Big Picture", true, "")));
        List<String> started = new ArrayList<>();
        registry.setStreamRequestHandler((client, app) -> started.add(app));
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        FakeLink tv = new FakeLink();
        assertTrue(registry.connected(new Message.Hello(Protocol.VERSION, "agent-2", "tv", null, "6.1.0"), tv));
        registry.approve("agent-2");
        registry.received("agent-2", tv, new Message.Status(ClientState.STREAMING, "Steam Big Picture"));

        registry.received("agent-1", link, new Message.AppListRequest());
        assertEquals(new Message.AppList(List.of("Steam Big Picture"), "Steam Big Picture", null,
                java.util.Arrays.asList((String) null)), awaitSent(link, Message.AppList.class));
        registry.received("agent-1", link, new Message.StreamRequest(null));
        assertEquals(List.of("Steam Big Picture"), started, "joins the running app rather than its last one");
    }

    @Test
    void appListSaysWhyWhenSunshineCantBeAsked() throws Exception {
        AgentRegistry registry = registry();
        registry.setAppSource(() -> {
            throw new java.io.IOException("connection refused");
        });
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        registry.received("agent-1", link, new Message.AppListRequest());
        Message.AppList list = awaitSent(link, Message.AppList.class);
        assertEquals(List.of(), list.apps());
        assertEquals("can't read Sunshine's apps: connection refused", list.error());
    }

    @Test
    void stopRequestGoesToTheHandler() {
        AgentRegistry registry = registry();
        List<String> stopped = new ArrayList<>();
        registry.setStopRequestHandler(client -> stopped.add(client.hostname()));
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        registry.received("agent-1", link, new Message.StopRequest());
        assertEquals(List.of("livingroom"), stopped);
    }

    @Test
    void stopCheckIsAnsweredWithWhatTheHandlerSays() throws Exception {
        AgentRegistry registry = registry();
        List<Boolean> remote = new ArrayList<>();
        registry.setStopCheckHandler((client, fromRemote) -> {
            remote.add(fromRemote);
            return new Message.StopCheck(client.currentApp(), fromRemote);
        });
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        registry.received("agent-1", link, new Message.Status(ClientState.STREAMING, "Steam Big Picture"));
        registry.received("agent-1", link, new Message.StopCheckRequest(true));
        assertEquals(new Message.StopCheck("Steam Big Picture", true), awaitSent(link, Message.StopCheck.class));
        assertEquals(List.of(true), remote);
    }

    @Test
    void aFailedStopCheckSaysNothingIsLost() throws Exception {
        AgentRegistry registry = registry();
        registry.setStopCheckHandler((client, fromRemote) -> {
            throw new java.io.IOException("Sunshine doesn't answer");
        });
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        registry.received("agent-1", link, new Message.StopCheckRequest(false));
        assertEquals(new Message.StopCheck(null, false), awaitSent(link, Message.StopCheck.class),
                "the client asked to stop; it shouldn't be stuck with a question");
    }

    @Test
    void focusRequestGoesToTheHandlerOnlyWhileStreaming() {
        AgentRegistry registry = registry();
        List<String> asked = new ArrayList<>();
        registry.setFocusRequestHandler((client, pointer) -> asked.add(client.hostname() + " " + pointer));
        FakeLink link = new FakeLink();
        approveNew(registry, link);

        registry.received("agent-1", link, new Message.FocusRequest(true));
        assertEquals(List.of(), asked, "no stream, nothing to focus");

        registry.received("agent-1", link, new Message.Status(ClientState.STREAMING, "Desktop"));
        registry.received("agent-1", link, new Message.FocusRequest(false));
        assertEquals(List.of("livingroom false"), asked);
    }

    @Test
    void welcomeOffersFocusRequestsOnlyWhereFocusCanBeMoved() {
        FakeLink without = new FakeLink();
        approveNew(registry(), without);
        assertTrue(welcomes(without).noneMatch(Message.Welcome::focusRequests));

        AgentRegistry registry = registry();
        registry.setFocusRequestHandler((client, pointer) -> { });
        FakeLink with = new FakeLink();
        approveNew(registry, with);
        assertTrue(welcomes(with).findAny().isPresent());
        assertTrue(welcomes(with).allMatch(Message.Welcome::focusRequests));
    }

    @Test
    void remoteActionsAreShownOnlyWhileStreamingAndOfferedOnlyWhereTheyCanBe() {
        FakeLink without = new FakeLink();
        approveNew(registry(), without);
        assertTrue(welcomes(without).noneMatch(Message.Welcome::remoteActions));

        AgentRegistry registry = registry();
        List<String> shown = new ArrayList<>();
        registry.setRemoteActionHandler((client, action) -> shown.add(client.hostname() + " " + action));
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        assertTrue(welcomes(link).allMatch(Message.Welcome::remoteActions));

        registry.received("agent-1", link, new Message.RemoteAction(Message.RemoteAction.MOUSE_MODE));
        assertEquals(List.of(), shown, "nothing streams, so nothing would see it");

        registry.received("agent-1", link, new Message.Status(ClientState.STREAMING, "Desktop"));
        registry.received("agent-1", link, new Message.RemoteAction(Message.RemoteAction.STOP));
        assertEquals(List.of("livingroom stop"), shown);
    }

    @Test
    void remembersTheLastAppStreamedForTheStartDialog() {
        AgentRegistry registry = registry();
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        assertEquals(AgentRegistry.DEFAULT_APP, registry.lastApp("agent-1"), "nothing streamed yet");

        registry.received("agent-1", link, new Message.Status(ClientState.STREAMING, "YouTube"));
        assertEquals("YouTube", registry.lastApp("agent-1"));
    }

    private static Stream<Message.Welcome> welcomes(FakeLink link) {
        return List.copyOf(link.sent).stream().filter(Message.Welcome.class::isInstance)
                .map(Message.Welcome.class::cast);
    }

    /** The app list is sent from another thread, since it asks Sunshine. */
    private static <T> T awaitSent(FakeLink link, Class<T> type) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            synchronized (link) {
                for (Message.FromHost m : List.copyOf(link.sent)) {
                    if (type.isInstance(m)) {
                        return type.cast(m);
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no " + type.getSimpleName() + " sent");
    }

    @Test
    void refusedStreamRequestTellsTheClientWhy() {
        AgentRegistry registry = registry();
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        registry.refuseStreamRequest("agent-1", "Sunshine isn't running on the host");
        assertEquals(new Message.StreamRequestRefused("Sunshine isn't running on the host"), link.last());
    }

    @Test
    void pendingClientsCantAskForStreams() {
        AgentRegistry registry = registry();
        List<String> started = new ArrayList<>();
        registry.setStreamRequestHandler((client, app) -> started.add(app));
        FakeLink link = new FakeLink();
        assertTrue(registry.connected(hello(null), link));
        registry.received("agent-1", link, new Message.StreamRequest());
        assertEquals(List.of(), started);
    }

    @Test
    void lastSeenSurvivesARestart() throws Exception {
        AgentRegistry first = registry();
        FakeLink link = new FakeLink();
        approveNew(first, link);
        first.disconnected("agent-1", link);
        java.time.Instant seen = first.clients().getFirst().lastSeen();
        assertNotNull(seen);

        AgentRegistry second = registry();
        second.load();
        ClientView reloaded = second.clients().getFirst();
        assertFalse(reloaded.online());
        assertEquals(seen, reloaded.lastSeen());
    }

    @Test
    void keepsAndSavesTheClientsVersions() throws Exception {
        AgentRegistry first = registry();
        FakeLink link = new FakeLink();
        assertTrue(first.connected(new Message.Hello(Protocol.VERSION, "agent-1", "livingroom", null, "6.1.0", "0.1.0"),
                link));
        first.approve("agent-1");
        first.disconnected("agent-1", link);

        AgentRegistry second = registry();
        second.load();
        ClientView offline = second.clients().getFirst();
        assertEquals("6.1.0", offline.moonlightVersion(), "offline clients keep their last known versions");
        assertEquals("0.1.0", offline.agentVersion());
        assertEquals(List.of("Orbit agent: 0.1.0", "Moonlight: 6.1.0"), offline.softwareLines());
    }

    @Test
    void keepsAndSavesTheClientsHardwareDecoders() throws Exception {
        AgentRegistry first = registry();
        FakeLink link = new FakeLink();
        assertTrue(first.connected(new Message.Hello(Protocol.VERSION, "agent-1", "livingroom", null, "6.1.0", "0.1.0",
                List.of("HEVC")), link));
        assertEquals(List.of("HEVC"), first.clients().getFirst().hardwareDecoders());
        first.approve("agent-1");
        first.disconnected("agent-1", link);

        AgentRegistry second = registry();
        second.load();
        assertEquals(List.of("HEVC"), second.clients().getFirst().hardwareDecoders(),
                "the settings dialog shows them for offline clients too");
    }

    @Test
    void olderAgentsWithoutAVersionAreShownAsUnknown() {
        AgentRegistry registry = registry();
        assertTrue(registry.connected(new Message.Hello(Protocol.VERSION, "agent-1", "livingroom", null, null), new FakeLink()));
        assertEquals(List.of("Orbit agent: unknown (older than 0.1.0)", "Moonlight: unknown"),
                registry.clients().getFirst().softwareLines());
        ClientView qt = new ClientView("a", "h", ClientView.Approval.APPROVED, true, null, null, StreamSettings.DEFAULT,
                null, "Moonlight 6.1.0", "0.1.0", false, false, null, false, null, null, false, false, null, null);
        assertEquals("Moonlight: 6.1.0", qt.softwareLines().get(1), "moonlight-qt's name isn't repeated");
    }

    @Test
    void showsTheTvRemoteWhileTheClientReadsIt() {
        AgentRegistry registry = registry();
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        assertFalse(registry.clients().getFirst().remote());

        registry.received("agent-1", link,
                new Message.Status(ClientState.AVAILABLE, null, false, null, null, false, true));
        ClientView client = registry.clients().getFirst();
        assertTrue(client.remote());
        assertTrue(client.stateLines().contains("TV remote: works with this client (HDMI-CEC)"));

        registry.disconnected("agent-1", link);
        assertFalse(registry.clients().getFirst().remote(), "an offline client's remote does nothing");
    }

    @Test
    void clientWithoutMoonlightCanOnlyBeSetUpOrRemoved() {
        AgentRegistry registry = registry();
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        registry.received("agent-1", link, new Message.Status(ClientState.ERROR, null, true, null));

        ClientView missing = registry.clients().getFirst();
        assertEquals("Moonlight missing", missing.statusLabel());
        assertEquals("Moonlight: not installed", missing.softwareLines().get(1));
        assertEquals(java.util.Set.of(ClientActions.Action.SETTINGS, ClientActions.Action.REMOVE),
                ClientActions.enabled(missing, false), "no play or pair without Moonlight");

        // Installed while the agent runs: the next status says so and brings the version.
        registry.received("agent-1", link, new Message.Status(ClientState.AVAILABLE, null, false, "Moonlight 6.1.0"));
        ClientView installed = registry.clients().getFirst();
        assertEquals("Available", installed.statusLabel());
        assertEquals("Moonlight: 6.1.0", installed.softwareLines().get(1));
        assertTrue(ClientActions.enabled(installed, false).contains(ClientActions.Action.START));
    }

    @Test
    void sunshineNotRunningShowsOnEveryClientAndBlocksPlayAndPair() {
        AgentRegistry registry = registry();
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        registry.received("agent-1", link, new Message.Status(ClientState.ERROR, null));
        List<String> changed = new ArrayList<>();
        registry.addListener(new AgentRegistry.Listener() {
            @Override public void clientChanged(ClientView client) { changed.add(client.statusLabel()); }
            @Override public void clientRemoved(String agentId) { }
        });

        registry.setSunshineRunning(false);
        assertEquals(new Message.SunshineState(false), link.last(), "the agent is told, so it doesn't think it's unpaired");
        ClientView down = registry.clients().getFirst();
        assertEquals("Sunshine not running", down.statusLabel());
        assertEquals(java.util.Set.of(ClientActions.Action.SETTINGS, ClientActions.Action.REMOVE),
                ClientActions.enabled(down, false));
        assertEquals(List.of("Sunshine not running"), changed, "views update when Sunshine stops");

        registry.received("agent-1", link, new Message.Status(ClientState.AVAILABLE, null));
        registry.setSunshineRunning(true);
        assertEquals("Available", registry.clients().getFirst().statusLabel());
        assertEquals(new Message.SunshineState(true), link.last(), "and told when it's back, to check pairing");

        registry.setSunshineRunning(false);
        FakeLink reconnecting = new FakeLink();
        String token = ((Message.Welcome) link.sent.stream().filter(m -> m instanceof Message.Welcome).findFirst()
                .orElseThrow()).token();
        assertTrue(registry.connected(hello(token), reconnecting));
        assertEquals(new Message.SunshineState(false), reconnecting.sent.getFirst(), "told before welcome");
        assertInstanceOf(Message.Welcome.class, reconnecting.sent.get(1));
    }

    @Test
    void tooltipSaysWhyAndWhetherSunshineListsTheClient() {
        AgentRegistry registry = registry();
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        registry.received("agent-1", link, new Message.Status(ClientState.ERROR, null, false, "Moonlight 6.1.0",
                "Moonlight can't reach Sunshine on 192.168.1.31: Failed to connect to gamingpc"));
        assertEquals(List.of("Problem: Moonlight can't reach Sunshine on 192.168.1.31: Failed to connect to gamingpc"),
                registry.clients().getFirst().stateLines(), "Sunshine's list isn't known yet");

        registry.setSunshinePairings(pairings("LivingRoom", "kitchen"));
        assertEquals("Sunshine: paired as livingroom", registry.clients().getFirst().stateLines().get(1),
                "names match regardless of case");
        registry.setSunshinePairings(pairings("moonlight"));
        assertEquals("Sunshine's paired clients: moonlight", registry.clients().getFirst().stateLines().get(1),
                "paired outside Orbit under Moonlight's default name: listed, not reported as a fault");

        registry.received("agent-1", link, new Message.Status(ClientState.AVAILABLE, null));
        assertEquals(List.of("Sunshine's paired clients: moonlight"), registry.clients().getFirst().stateLines(),
                "no problem line once it's fine");
    }

    private static List<SunshineClient.PairedClient> pairings(String... names) {
        return java.util.Arrays.stream(names).map(n -> new SunshineClient.PairedClient(n, "u-" + n)).toList();
    }

    @Test
    void pairingsWithoutAnApprovedClientAreListedForUnpairing() {
        AgentRegistry registry = registry();
        assertEquals(List.of(), registry.agentlessPairings(), "Sunshine's list isn't known yet");
        int[] changes = {0};
        registry.addListener(new AgentRegistry.Listener() {
            @Override
            public void clientChanged(ClientView client) {
            }

            @Override
            public void clientRemoved(String agentId) {
            }

            @Override
            public void pairingsChanged() {
                changes[0]++;
            }
        });

        FakeLink link = new FakeLink();
        assertTrue(registry.connected(hello(null), link));
        registry.setSunshinePairings(pairings("phone", "LivingRoom", "moonlight"));
        assertEquals(1, changes[0], "told even without approved clients to update");
        assertEquals(List.of("LivingRoom", "moonlight", "phone"),
                registry.agentlessPairings().stream().map(ClientView::hostname).toList(),
                "a pending agent doesn't own a pairing yet");

        registry.approve("agent-1");
        List<ClientView> agentless = registry.agentlessPairings();
        assertEquals(List.of("moonlight", "phone"), agentless.stream().map(ClientView::hostname).toList(),
                "the approved client's pairing is its own, regardless of case");
        ClientView phone = agentless.get(1);
        assertTrue(phone.agentless());
        assertEquals("u-phone", phone.pairingUuid());
        assertEquals("Paired, no agent", phone.statusLabel());
        assertEquals(java.util.EnumSet.of(ClientActions.Action.UNPAIR), ClientActions.enabled(phone, false));
        assertEquals(java.util.Set.of(), ClientActions.enabled(phone, true), "not while it's being unpaired");
        assertFalse(registry.clients().stream().anyMatch(ClientView::agentless), "not one of the agents' clients");

        registry.setSunshinePairings(pairings("phone", "LivingRoom", "moonlight"));
        assertEquals(1, changes[0], "unchanged list: no update");
        registry.setSunshineRunning(false);
        assertEquals(java.util.Set.of(), ClientActions.enabled(registry.agentlessPairings().getFirst(), false),
                "Sunshine must answer to unpair");
    }

    @Test
    void aClientDisabledInSunshineCantStart() {
        AgentRegistry registry = registry();
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        registry.received("agent-1", link, new Message.Status(ClientState.AVAILABLE, null));
        registry.setSunshinePairings(List.of(new SunshineClient.PairedClient("LivingRoom", "u-1", false),
                new SunshineClient.PairedClient("phone", "u-2", false)));
        ClientView client = registry.clients().getFirst();
        assertTrue(client.disabledInSunshine(), "names match regardless of case");
        assertEquals("Disabled in Sunshine", client.statusLabel());
        assertEquals("Sunshine: paired as livingroom, but disabled there, so it can't stream",
                client.stateLines().getFirst());
        assertFalse(ClientActions.enabled(client, false).contains(ClientActions.Action.START));
        assertTrue(ClientActions.enabled(client, false).contains(ClientActions.Action.UNPAIR));
        assertEquals("this client is disabled in Sunshine", ClientActions.startRefusal(client, false, null));
        assertEquals("Disabled, no agent", registry.agentlessPairings().getFirst().statusLabel());
        registry.received("agent-1", link, new Message.Status(ClientState.UNPAIRED, null));
        client = registry.clients().getFirst();
        assertEquals("Disabled in Sunshine", client.statusLabel(), "Moonlight takes it for unpaired");
        assertFalse(ClientActions.enabled(client, false).contains(ClientActions.Action.PAIR),
                "enabling it is the fix, not pairing again");
        assertTrue(ClientActions.enabled(client, false).contains(ClientActions.Action.UNPAIR));

        registry.setSunshinePairings(List.of(new SunshineClient.PairedClient("livingroom", "u-1", false),
                new SunshineClient.PairedClient("livingroom", "u-3", true)));
        assertInstanceOf(Message.CheckPairing.class, link.last(), "enabled again: the agent checks at once");
        registry.received("agent-1", link, new Message.Status(ClientState.AVAILABLE, null));
        client = registry.clients().getFirst();
        assertFalse(client.disabledInSunshine(), "one enabled pairing is enough");
        assertTrue(ClientActions.enabled(client, false).contains(ClientActions.Action.START));
    }

    @Test
    void waitingRequestsAreListedUnlessAnAgentIsPairingFromThatAddress() {
        AgentRegistry registry = registry();
        FakeLink link = new FakeLink();
        link.address = "192.168.1.50";
        approveNew(registry, link);
        SunshineClient.PendingPairing phone = new SunshineClient.PendingPairing("a1", "Pixel", "::ffff:192.168.1.77");
        SunshineClient.PendingPairing tv = new SunshineClient.PendingPairing("b2", "", "::ffff:192.168.1.50");
        registry.setPendingPairings(List.of(phone, tv));

        List<ClientView> rows = registry.pairingRequests();
        assertEquals(List.of("Pixel", "::ffff:192.168.1.50"), rows.stream().map(ClientView::hostname).toList(),
                "a request without a name goes by its address");
        ClientView pixel = rows.getFirst();
        assertTrue(pixel.agentless());
        assertEquals("Wants to pair", pixel.statusLabel());
        assertEquals("Moonlight on 192.168.1.77 wants to pair with Sunshine", pixel.stateLines().getFirst());
        assertEquals(java.util.EnumSet.of(ClientActions.Action.PAIR), ClientActions.enabled(pixel, false));

        registry.pair("agent-1");
        assertEquals(List.of("Pixel"), registry.pairingRequests().stream().map(ClientView::hostname).toList(),
                "the agent's own pairing gets its PIN from the agent");
        assertFalse(registry.clients().stream().anyMatch(ClientView::agentless));
    }

    @Test
    void shutdownRecordsConnectedClientsAsSeenThen() throws Exception {
        java.time.Instant connectedAt = java.time.Instant.parse("2026-10-11T10:00:00Z");
        java.time.Instant shutdownAt = java.time.Instant.parse("2026-10-11T12:30:00Z");
        AtomicReference<java.time.Instant> now = new AtomicReference<>(connectedAt);
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public java.time.Instant instant() { return now.get(); }
        };
        AgentRegistry first = new AgentRegistry(dir.resolve("clients.json"), clock);
        approveNew(first, new FakeLink());
        now.set(shutdownAt);
        first.saveOnShutdown();

        AgentRegistry second = registry();
        second.load();
        assertEquals(shutdownAt, second.clients().getFirst().lastSeen());
    }

    @Test
    void loadsClientsSavedWithoutLastSeen() throws Exception {
        Files.writeString(dir.resolve("clients.json"), """
                {"clients":[{"agentId":"agent-1","hostname":"livingroom","tokenHash":"x",
                 "settings":{"width":1920,"height":1080,"fps":60,"bitrateKbps":20000,"extraFlags":[]}}]}
                """);
        AgentRegistry registry = registry();
        registry.load();
        assertNull(registry.clients().getFirst().lastSeen());
    }

    @Test
    void approvedAgentReconnectsWithTokenAfterRestart() throws Exception {
        AgentRegistry first = registry();
        String token = approveNew(first, new FakeLink());

        AgentRegistry second = registry();
        second.load();
        FakeLink link = new FakeLink();
        assertTrue(second.connected(hello(token), link));
        Message.Welcome welcome = assertInstanceOf(Message.Welcome.class, link.last());
        assertNull(welcome.token(), "existing token must not be reissued");
        assertFalse(Files.readString(dir.resolve("clients.json")).contains(token), "token must be stored hashed");
    }

    @Test
    void wrongTokenIsRejected() {
        AgentRegistry registry = registry();
        approveNew(registry, new FakeLink());

        FakeLink intruder = new FakeLink();
        assertFalse(registry.connected(hello("guess"), intruder));
        assertInstanceOf(Message.Rejected.class, intruder.last());
        assertTrue(intruder.closed);
    }

    @Test
    void protocolMismatchIsRejected() {
        FakeLink link = new FakeLink();
        assertFalse(registry().connected(new Message.Hello(99, "a", "h", null, null), link));
        assertInstanceOf(Message.Rejected.class, link.last());
    }

    @Test
    void newConnectionReplacesOld() {
        AgentRegistry registry = registry();
        FakeLink old = new FakeLink();
        String token = approveNew(registry, old);

        FakeLink fresh = new FakeLink();
        assertTrue(registry.connected(hello(token), fresh));
        assertTrue(old.closed);

        registry.disconnected("agent-1", old);
        assertTrue(registry.clients().getFirst().online(), "stale link must not mark the client offline");
    }

    @Test
    void pendingAgentIsForgottenOnDisconnect() {
        AgentRegistry registry = registry();
        FakeLink link = new FakeLink();
        registry.connected(hello(null), link);
        registry.disconnected("agent-1", link);
        assertTrue(registry.clients().isEmpty());
    }

    @Test
    void statusUpdatesView() {
        AgentRegistry registry = registry();
        FakeLink link = new FakeLink();
        approveNew(registry, link);

        registry.received("agent-1", link, new Message.Status(ClientState.STREAMING, "Desktop"));
        ClientView view = registry.clients().getFirst();
        assertEquals(ClientState.STREAMING, view.state());
        assertEquals("Desktop", view.currentApp());
        assertEquals("Streaming", view.statusLabel());

        registry.disconnected("agent-1", link);
        assertEquals("Offline", registry.clients().getFirst().statusLabel());
    }

    @Test
    void commandsCompleteWithAgentResult() throws Exception {
        AgentRegistry registry = registry();
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        StreamSettings settings = new StreamSettings(1280, 720, 30, 5000, List.of());
        registry.updateSettings("agent-1", settings);
        assertEquals(new Message.ApplySettings(settings), link.last());

        CompletableFuture<Message.CommandResult> result = registry.startStream("agent-1", "Desktop");
        Message.StartStream start = assertInstanceOf(Message.StartStream.class, link.last());
        assertEquals(settings, start.settings());
        assertFalse(result.isDone());

        registry.received("agent-1", link, Message.CommandResult.success(start.cmdId()));
        assertTrue(result.get().ok());
    }

    @Test
    void commandsFailWhenAgentDisconnects() {
        AgentRegistry registry = registry();
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        CompletableFuture<Message.CommandResult> result = registry.stopStream("agent-1");
        registry.disconnected("agent-1", link);
        assertTrue(result.isCompletedExceptionally());
    }

    @Test
    void commandsToPendingAgentsFail() {
        AgentRegistry registry = registry();
        registry.connected(hello(null), new FakeLink());
        var e = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                () -> registry.startStream("agent-1", "Desktop").get());
        assertTrue(e.getCause().getMessage().contains("not approved"));
    }

    @Test
    void pairPinIsSubmittedAndFailureReported() throws Exception {
        AgentRegistry registry = registry();
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        AtomicReference<String> submitted = new AtomicReference<>();
        registry.setPinHandler((client, pin, address) -> {
            submitted.set(client.hostname() + ":" + pin);
            return CompletableFuture.failedFuture(new IllegalStateException("bad credentials"));
        });

        CompletableFuture<Message.CommandResult> result = registry.pair("agent-1");
        Message.BeginPairing begin = assertInstanceOf(Message.BeginPairing.class, link.last());
        registry.received("agent-1", link, new Message.PairPin(begin.cmdId(), "4321"));

        assertEquals("livingroom:4321", submitted.get());
        Message.CommandResult r = result.get();
        assertFalse(r.ok());
        assertTrue(r.error().contains("bad credentials"), r.error());
        assertTrue(link.sent.contains(new Message.PairingDone(begin.cmdId(), false)),
                "the agent is told, so it closes Moonlight's window: " + link.sent);
    }

    @Test
    void agentIsToldWhenSunshineAcceptsThePin() {
        AgentRegistry registry = registry();
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        registry.setPinHandler((client, pin, address) -> CompletableFuture.completedFuture(null));
        registry.pair("agent-1");
        Message.BeginPairing begin = assertInstanceOf(Message.BeginPairing.class, link.last());
        registry.received("agent-1", link, new Message.PairPin(begin.cmdId(), "4321"));
        assertEquals(new Message.PairingDone(begin.cmdId(), true), link.last());
    }

    @Test
    void unsolicitedPinIsIgnored() {
        AgentRegistry registry = registry();
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        AtomicReference<String> submitted = new AtomicReference<>();
        registry.setPinHandler((client, pin, address) -> {
            submitted.set(pin);
            return CompletableFuture.completedFuture(null);
        });
        registry.received("agent-1", link, new Message.PairPin("made-up", "0000"));
        assertNull(submitted.get());
    }

    @Test
    void removeDisconnectsAndForgets() throws Exception {
        AgentRegistry registry = registry();
        FakeLink link = new FakeLink();
        approveNew(registry, link);
        registry.remove("agent-1");
        assertTrue(link.closed);
        assertTrue(registry.clients().isEmpty());

        AgentRegistry reloaded = registry();
        reloaded.load();
        assertTrue(reloaded.clients().isEmpty());
    }
}
