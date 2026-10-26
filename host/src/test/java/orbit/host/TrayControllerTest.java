package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import orbit.host.ClientActions.Action;
import orbit.tray.TrayMenu.Entry;
import orbit.tray.TrayMenu.Item;
import orbit.protocol.ClientState;
import orbit.protocol.StreamSettings;
import org.junit.jupiter.api.Test;

class TrayControllerTest {

    /** Records which window action the menu ran, e.g. "stop livingroom". */
    private static class RecordingWindow implements TrayController.Window {
        final List<String> calls = new ArrayList<>();

        @Override public void show() { calls.add("show"); }
        @Override public void quit() { calls.add("quit"); }
        @Override public void approve(ClientView c) { calls.add("approve " + c.hostname()); }
        @Override public void pair(ClientView c) { calls.add("pair " + c.hostname()); }
        @Override public void unpair(ClientView c) { calls.add("unpair " + c.hostname()); }
        @Override public void startStream(ClientView c, String app) { calls.add("start " + c.hostname() + " " + app); }
        @Override public void chooseAppAndStart(ClientView c) { calls.add("choose " + c.hostname()); }
        @Override public void stopStream(ClientView c) { calls.add("stop " + c.hostname()); }
        @Override public void editSettings(ClientView c) { calls.add("settings " + c.hostname()); }
        @Override public void remove(ClientView c) { calls.add("remove " + c.hostname()); }
        @Override public String activity(String agentId) { return null; }
        @Override public void addChangeListener(Runnable listener) { }
    }

    private static ClientView client(String name, ClientView.Approval approval, boolean online, ClientState state,
            String app) {
        return new ClientView("id-" + name, name, approval, online, state, app, StreamSettings.DEFAULT, Instant.EPOCH,
                "6.1.0", "0.1.0", false, false, null, false, null, null, false, false, null, null);
    }

    private static Item item(List<Entry> entries, String label) {
        return entries.stream().filter(e -> e instanceof Item i && i.label().equals(label)).map(Item.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("no item " + label + " in " + labels(entries)));
    }

    private static List<String> labels(List<Entry> entries) {
        return entries.stream().map(e -> e instanceof Item i ? i.label() : "---").toList();
    }

    @Test
    void listsEveryClientWithItsStatus() {
        RecordingWindow w = new RecordingWindow();
        List<Entry> menu = TrayController.menu(List.of(
                client("livingroom", ClientView.Approval.APPROVED, true, ClientState.STREAMING, "Desktop"),
                client("kitchen", ClientView.Approval.APPROVED, true, ClientState.AVAILABLE, null),
                client("attic", ClientView.Approval.APPROVED, false, ClientState.AVAILABLE, null),
                client("newbie", ClientView.Approval.PENDING, true, null, null)),
                id -> null, List.of(), w);
        assertEquals(List.of("livingroom — Streaming Desktop", "kitchen — Available", "attic — Offline",
                "newbie — Awaiting approval"), labels(menu));
    }

    @Test
    void emptyMenuSaysThereAreNoClients() {
        List<Entry> menu = TrayController.menu(List.of(), id -> null, List.of(), new RecordingWindow());
        assertEquals(List.of("No clients yet"), labels(menu));
        assertFalse(item(menu, "No clients yet").enabled());
    }

    @Test
    void availableClientCanStartAnySunshineApp() {
        RecordingWindow w = new RecordingWindow();
        List<Entry> menu = TrayController.menu(
                List.of(client("kitchen", ClientView.Approval.APPROVED, true, ClientState.AVAILABLE, null)),
                id -> null, List.of("Desktop", "Steam Big Picture"), w);
        List<Entry> actions = item(menu, "kitchen — Available").children();
        assertEquals(List.of("Start stream", "Stream settings…", "---", "Remove client…"), labels(actions));

        List<Entry> apps = item(actions, "Start stream").children();
        assertEquals(List.of("Desktop", "Steam Big Picture", "---", "Other app…"), labels(apps));
        item(apps, "Steam Big Picture").action().run();
        item(apps, "Other app…").action().run();
        item(actions, "Stream settings…").action().run();
        item(actions, "Remove client…").action().run();
        assertEquals(List.of("start kitchen Steam Big Picture", "choose kitchen", "settings kitchen", "remove kitchen"),
                w.calls);
    }

    @Test
    void offersDesktopBeforeSunshineAppsAreKnown() {
        List<Entry> menu = TrayController.menu(
                List.of(client("kitchen", ClientView.Approval.APPROVED, true, ClientState.AVAILABLE, null)),
                id -> null, List.of(), new RecordingWindow());
        List<Entry> apps = item(item(menu, "kitchen — Available").children(), "Start stream").children();
        assertEquals(List.of("Desktop", "---", "Other app…"), labels(apps));
    }

    @Test
    void stoppingClientShowsActivityAndDisablesStop() {
        RecordingWindow w = new RecordingWindow();
        ClientView streaming = client("livingroom", ClientView.Approval.APPROVED, true, ClientState.STREAMING, "Desktop");
        List<Entry> normal = item(TrayController.menu(List.of(streaming), id -> null, List.of(), w),
                "livingroom — Streaming Desktop").children();
        assertEquals(List.of("Stop stream", "Stream settings…", "---", "Remove client…"), labels(normal));
        assertTrue(item(normal, "Stop stream").enabled());

        Map<String, String> activities = Map.of(streaming.agentId(), "Stopping…");
        List<Entry> stopping = item(TrayController.menu(List.of(streaming), activities::get, List.of(), w),
                "livingroom — Stopping…").children();
        assertFalse(item(stopping, "Stop stream").enabled());
    }

    @Test
    void crownsTheLead() {
        ClientView tv = client("tv", ClientView.Approval.APPROVED, true, ClientState.STREAMING, "Desktop");
        ClientView bedroom = client("bedroom", ClientView.Approval.APPROVED, true, ClientState.STREAMING, "Desktop");
        RecordingWindow w = new RecordingWindow() {
            @Override
            public boolean setsHostMonitor(String agentId) {
                return agentId.equals(tv.agentId());
            }
        };
        List<Entry> menu = TrayController.menu(List.of(tv, bedroom), id -> null, List.of(), w);
        assertSame(TrayController.LEAD_ICON, item(menu, "tv — Streaming Desktop").iconPng());
        assertNull(item(menu, "bedroom — Streaming Desktop").iconPng());
        assertNotEquals(item(menu, "tv — Streaming Desktop").key(),
                item(TrayController.menu(List.of(tv), id -> null, List.of(), new RecordingWindow()),
                        "tv — Streaming Desktop").key(), "a new item when the crown leaves");
    }

    @Test
    void streamRequestsAreRefusedWhenStartWouldBeGreyedOut() {
        ClientView ready = client("tv", ClientView.Approval.APPROVED, true, ClientState.AVAILABLE, null);
        assertNull(ClientActions.startRefusal(ready, false, null));
        assertEquals("Orbit is busy with this client", ClientActions.startRefusal(ready, true, null));
        assertEquals("Sunshine streams to 1 client at a time",
                ClientActions.startRefusal(ready, false, "Sunshine streams to 1 client at a time"));
        assertEquals("not paired yet", ClientActions.startRefusal(
                client("tv", ClientView.Approval.APPROVED, true, ClientState.UNPAIRED, null), false, null));
        assertEquals("already streaming", ClientActions.startRefusal(
                client("tv", ClientView.Approval.APPROVED, true, ClientState.STREAMING, "Desktop"), false, null));
    }

    @Test
    void startIsGreyedOutWhileSunshineHasNoRoom() {
        ClientView tv = client("tv", ClientView.Approval.APPROVED, true, ClientState.STREAMING, "Desktop");
        ClientView kitchen = client("kitchen", ClientView.Approval.APPROVED, true, ClientState.AVAILABLE, null);
        List<ClientView> clients = List.of(tv, kitchen);
        assertEquals("Sunshine streams to 1 client at a time", ClientActions.streamLimit(clients, kitchen, 1));
        assertNull(ClientActions.streamLimit(clients, tv, 1), "its own stream doesn't count");
        assertNull(ClientActions.streamLimit(clients, kitchen, 2));

        RecordingWindow w = new RecordingWindow() {
            @Override
            public String streamLimit(ClientView c) {
                return ClientActions.streamLimit(clients, c, 1);
            }
        };
        List<Entry> items = item(TrayController.menu(clients, id -> null, List.of(), w), "kitchen — Available")
                .children();
        Entry start = items.getFirst();
        assertEquals("Start stream (Sunshine streams to 1 client at a time)", ((Item) start).label());
        assertFalse(((Item) start).enabled());
    }

    @Test
    void pairingClientShowsActivityAndOffersNoSecondPairing() {
        ClientView den = client("den", ClientView.Approval.APPROVED, true, ClientState.UNPAIRED, null);
        assertTrue(ClientActions.enabled(den, false).contains(Action.PAIR));
        assertFalse(ClientActions.enabled(den, true).contains(Action.PAIR), "already pairing");
        List<Entry> menu = TrayController.menu(List.of(den), Map.of(den.agentId(), "Pairing…")::get, List.of(),
                new RecordingWindow());
        assertEquals(List.of("Stream settings…", "---", "Remove client…"),
                labels(item(menu, "den — Pairing…").children()));
    }

    @Test
    void clientThatDoesntAllowStreamsOffersNoStart() {
        ClientView den = new ClientView("id-den", "den", ClientView.Approval.APPROVED, true, ClientState.AVAILABLE,
                null, StreamSettings.DEFAULT, Instant.EPOCH, "6.1.0", "0.1.0", false, false, null, true, null, null,
                false, false, null, null);
        assertEquals("Not allowing streams", den.statusLabel());
        assertFalse(ClientActions.enabled(den, false).contains(Action.START));
        assertTrue(den.stateLines().contains("Streams are turned off on the client, in its tray icon"));
    }

    @Test
    void unpairIsOfferedWhileSunshineHasTheClientPaired() {
        ClientView tv = pairedInSunshine("tv", true, ClientState.AVAILABLE);
        assertTrue(ClientActions.enabled(tv, false).contains(Action.UNPAIR));
        assertTrue(ClientActions.enabled(pairedInSunshine("tv", false, ClientState.AVAILABLE), false)
                .contains(Action.UNPAIR), "done in Sunshine alone, so also while the client is offline");
        assertFalse(ClientActions.enabled(tv, true).contains(Action.UNPAIR), "not while busy");
        assertFalse(ClientActions.enabled(client("tv", ClientView.Approval.APPROVED, true, ClientState.AVAILABLE,
                null), false).contains(Action.UNPAIR), "paired under another name: nothing Orbit can match");
        ClientView unpaired = pairedInSunshine("tv", true, ClientState.UNPAIRED);
        assertEquals(Set.of(Action.PAIR, Action.SETTINGS, Action.REMOVE), ClientActions.enabled(unpaired, false),
                "a client that says it isn't paired is paired again, even if an old pairing is left in Sunshine");

        RecordingWindow w = new RecordingWindow();
        item(TrayController.menu(List.of(tv), id -> null, List.of(), w), "tv — Available").children().stream()
                .filter(e -> e instanceof Item i && i.label().equals("Unpair…")).map(e -> (Item) e)
                .findFirst().orElseThrow().action().run();
        assertEquals(List.of("unpair tv"), w.calls);
    }

    private static ClientView pairedInSunshine(String name, boolean online, ClientState state) {
        return new ClientView("id-" + name, name, ClientView.Approval.APPROVED, online, state, null,
                StreamSettings.DEFAULT, Instant.EPOCH, "6.1.0", "0.1.0", false, false, null, false,
                List.of("Moonlight", name.toUpperCase()), null, false, false, null, null);
    }

    @Test
    void startingClientShowsActivityAndOffersNoStart() {
        ClientView kitchen = client("kitchen", ClientView.Approval.APPROVED, true, ClientState.AVAILABLE, null);
        Map<String, String> activities = Map.of(kitchen.agentId(), "Starting…");
        List<Entry> menu = TrayController.menu(List.of(kitchen), activities::get, List.of(), new RecordingWindow());
        assertEquals(List.of("Stream settings…", "---", "Remove client…"),
                labels(item(menu, "kitchen — Starting…").children()));
    }

    @Test
    void pendingAndUnpairedClientsOfferApproveAndPair() {
        RecordingWindow w = new RecordingWindow();
        List<Entry> menu = TrayController.menu(List.of(
                client("newbie", ClientView.Approval.PENDING, true, null, null),
                client("fresh", ClientView.Approval.APPROVED, true, ClientState.UNPAIRED, null)),
                id -> null, List.of(), w);
        List<Entry> pending = item(menu, "newbie — Awaiting approval").children();
        assertEquals(List.of("Approve", "---", "Remove client…"), labels(pending));
        List<Entry> unpaired = item(menu, "fresh — Not paired").children();
        assertEquals(List.of("Pair", "Stream settings…", "---", "Remove client…"), labels(unpaired),
                "no stream before pairing");
        item(pending, "Approve").action().run();
        item(unpaired, "Pair").action().run();
        assertEquals(List.of("approve newbie", "pair fresh"), w.calls);
    }

    @Test
    void offlineClientOnlyOffersSettingsAndRemove() {
        List<Entry> menu = TrayController.menu(
                List.of(client("attic", ClientView.Approval.APPROVED, false, ClientState.STREAMING, "Desktop")),
                id -> null, List.of(), new RecordingWindow());
        assertEquals(List.of("Stream settings…", "---", "Remove client…"),
                labels(item(menu, "attic — Offline").children()));
    }

    @Test
    void itemKeysStayTheSameWhenTheStatusChanges() {
        RecordingWindow w = new RecordingWindow();
        Item before = (Item) TrayController.menu(
                List.of(client("kitchen", ClientView.Approval.APPROVED, true, ClientState.AVAILABLE, null)),
                id -> null, List.of(), w).getFirst();
        Item after = (Item) TrayController.menu(
                List.of(client("kitchen", ClientView.Approval.APPROVED, true, ClientState.STREAMING, "Desktop")),
                id -> null, List.of(), w).getFirst();
        assertEquals(before.key(), after.key());
        assertFalse(before.label().equals(after.label()));
    }

    @Test
    void noPlayUntilPairedAndReady() {
        for (ClientState state : new ClientState[] {ClientState.UNPAIRED, ClientState.ERROR, null}) {
            assertFalse(ClientActions.enabled(client("a", ClientView.Approval.APPROVED, true, state, null), false)
                    .contains(Action.START), "state " + state);
        }
        assertTrue(ClientActions.enabled(client("a", ClientView.Approval.APPROVED, true, ClientState.AVAILABLE, null),
                false).contains(Action.START));
    }

    @Test
    void clientActionsMatchState() {
        assertEquals(Set.of(Action.APPROVE, Action.REMOVE), ClientActions.enabled(
                client("a", ClientView.Approval.PENDING, true, ClientState.AVAILABLE, null), false));
        assertEquals(Set.of(Action.START, Action.SETTINGS, Action.REMOVE), ClientActions.enabled(
                client("a", ClientView.Approval.APPROVED, true, ClientState.AVAILABLE, null), false));
        assertEquals(Set.of(Action.PAIR, Action.SETTINGS, Action.REMOVE), ClientActions.enabled(
                client("a", ClientView.Approval.APPROVED, true, ClientState.UNPAIRED, null), false));
        assertEquals(Set.of(Action.SETTINGS, Action.REMOVE), ClientActions.enabled(
                client("a", ClientView.Approval.APPROVED, true, ClientState.ERROR, null), false),
                "pairing doesn't fix an error such as Moonlight not reaching Sunshine");
        assertEquals(Set.of(Action.STOP, Action.SETTINGS, Action.REMOVE), ClientActions.enabled(
                client("a", ClientView.Approval.APPROVED, true, ClientState.STREAMING, "Desktop"), false));
        assertEquals(Set.of(Action.SETTINGS, Action.REMOVE), ClientActions.enabled(
                client("a", ClientView.Approval.APPROVED, true, ClientState.STREAMING, "Desktop"), true));
        assertEquals(Set.of(Action.SETTINGS, Action.REMOVE), ClientActions.enabled(
                client("a", ClientView.Approval.APPROVED, false, ClientState.AVAILABLE, null), false));
        assertEquals(Set.of(Action.SETTINGS, Action.REMOVE), ClientActions.enabled(
                client("a", ClientView.Approval.APPROVED, true, ClientState.AVAILABLE, null), true),
                "no second start while one is starting");
    }
}
