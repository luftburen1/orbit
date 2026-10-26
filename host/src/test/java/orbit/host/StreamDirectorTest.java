package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import orbit.protocol.ClientState;
import orbit.protocol.StreamSettings;
import org.junit.jupiter.api.Test;

class StreamDirectorTest {

    private final AgentRegistry registry = new AgentRegistry(null, Clock.systemUTC());
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final FakeDisplay display = new FakeDisplay(events);
    private final AtomicReference<String> sunshineOutput = new AtomicReference<>("DP-2");
    private final StreamDirector director = new StreamDirector(registry, new HostResolution(display),
            () -> Optional.ofNullable(sunshineOutput.get()), Duration.ofMillis(50));

    private static final String ORIGINAL = "1920×1080 @ 120 Hz";

    private ScriptedAgent agent(String id, ClientState state, String app) {
        return new ScriptedAgent(registry, events, id, state, app);
    }

    private void settings(String id, int w, int h, int fps) {
        registry.updateSettings(id, new StreamSettings(w, h, fps, 20_000, List.of()));
    }

    private void awaitMode(String expected) throws InterruptedException {
        for (int i = 0; i < 100 && !display.current("DP-2").equals(expected); i++) {
            Thread.sleep(20);
        }
        assertEquals(expected, display.current("DP-2"));
    }

    @Test
    void setsMonitorBeforeStreamAndRestoresAfter() throws Exception {
        ScriptedAgent tv = agent("tv", ClientState.AVAILABLE, null);
        settings("tv", 2560, 1440, 60);

        StreamDirector.StartResult result = director.start("tv", "Desktop");

        assertTrue(result.ok());
        assertEquals("DP-2 set to 2560×1440 @ 60 Hz", result.displayNote());
        assertEquals(List.of("mode DP-2 2560×1440 @ 60 Hz", "start tv Desktop 2560x1440"), events,
                "monitor must change before the stream starts");

        Thread.sleep(150);
        assertEquals("2560×1440 @ 60 Hz", display.current("DP-2"), "kept while streaming");

        tv.report(ClientState.AVAILABLE, null);
        awaitMode(ORIGINAL);
    }

    @Test
    void leavesTheModeAloneWhenNotMatchingTheResolution() throws Exception {
        ScriptedAgent tv = agent("tv", ClientState.AVAILABLE, null);
        settings("tv", 2560, 1440, 60);
        director.setMatchResolution(false);

        StreamDirector.StartResult result = director.start("tv", "Desktop");

        assertTrue(result.ok());
        assertNull(result.displayNote());
        assertEquals(List.of("start tv Desktop 2560x1440"), events);
        assertNull(director.modeOwner());
        tv.report(ClientState.AVAILABLE, null);
        Thread.sleep(150);
        assertEquals(ORIGINAL, display.current("DP-2"));
    }

    @Test
    void placesTheStreamsWindowsUntilNothingStreams() throws Exception {
        List<String> placed = new CopyOnWriteArrayList<>();
        director.setWindowPlacer(new StreamDirector.WindowPlacer() {
            @Override
            public void streamStarting(String output, String app) {
                placed.add("place " + app + " on " + output);
            }

            @Override
            public void idle() {
                placed.add("idle");
            }
        });
        ScriptedAgent tv = agent("tv", ClientState.AVAILABLE, null);

        assertTrue(director.start("tv", "Steam Big Picture").ok());
        assertEquals(List.of("place Steam Big Picture on DP-2"), placed, "before the client starts Moonlight");
        Thread.sleep(150);
        assertEquals(List.of("place Steam Big Picture on DP-2"), placed, "kept while streaming");

        tv.report(ClientState.AVAILABLE, null);
        for (int i = 0; i < 100 && placed.size() < 2; i++) {
            Thread.sleep(20);
        }
        assertEquals("idle", placed.get(1));
    }

    private List<String> recordPlacing(StreamDirector d) {
        List<String> placed = new CopyOnWriteArrayList<>();
        d.setWindowPlacer(new StreamDirector.WindowPlacer() {
            @Override
            public void streamStarting(String output, String app) {
                placed.add("place " + app);
            }

            @Override
            public void idle() {
                placed.add("idle");
            }
        });
        return placed;
    }

    @Test
    void stopsPlacingWindowsAsSoonAsTheStreamEndsNotAfterTheRestoreDelay() throws Exception {
        // Steam's undo brings its main window back right as the stream ends; it must not go fullscreen on Sunshine's
        // monitor, which then turns off and leaves it fullscreen on the other one.
        try (StreamDirector slow = new StreamDirector(registry, new HostResolution(display),
                () -> Optional.ofNullable(sunshineOutput.get()), Duration.ofSeconds(30))) {
            List<String> placed = recordPlacing(slow);
            ScriptedAgent tv = agent("tv", ClientState.AVAILABLE, null);
            assertTrue(slow.start("tv", "Steam Big Picture").ok());

            tv.report(ClientState.AVAILABLE, null);
            for (int i = 0; i < 100 && placed.size() < 2; i++) {
                Thread.sleep(20);
            }
            assertEquals(List.of("place Steam Big Picture", "idle"), placed);
        }
    }

    @Test
    void stopsPlacingWindowsBeforeQuittingTheApp() throws Exception {
        // The client reports the stream ended only once Sunshine has quit the app and run its undo command, which
        // brings Steam's main window back: that must not be taken for one of Big Picture's and made fullscreen.
        director.setWindowPlacer(new StreamDirector.WindowPlacer() {
            @Override
            public void streamStarting(String output, String app) {
            }

            @Override
            public void idle() {
                events.add("idle");
            }
        });
        agent("tv", ClientState.STREAMING, "Steam Big Picture");
        agent("bedroom", ClientState.STREAMING, "Steam Big Picture");

        assertTrue(director.stop("bedroom").get().ok());
        assertEquals(List.of("disconnect bedroom"), events, "tv still streams the app");

        events.clear();
        assertTrue(director.stop("tv").get().ok());
        assertEquals(List.of("idle", "stop tv"), events.subList(0, 2));
    }

    @Test
    void stopsPlacingWindowsWhenTheStartFails() throws Exception {
        // No stream ends then, and the app's "Starting…" notice would stay up until it timed out.
        List<String> placed = recordPlacing(director);
        ScriptedAgent tv = agent("tv", ClientState.AVAILABLE, null);
        tv.failingStarts.set(1);

        assertEquals("host not ready", director.start("tv", "Zwift").result().error());
        assertEquals(List.of("place Zwift", "idle"), placed);
    }

    @Test
    void unlocksBeforeTheStreamAndLeavesItUnlocked() throws Exception {
        ScreenUnlockTest.FakeLock lock = new ScreenUnlockTest.FakeLock(events, true);
        director.setScreenUnlocker(new ScreenUnlock(lock, () -> true, Duration.ZERO));
        ScriptedAgent tv = agent("tv", ClientState.AVAILABLE, null);
        settings("tv", 2560, 1440, 60);

        assertTrue(director.start("tv", "Desktop").ok());
        assertEquals(List.of("unlock", "mode DP-2 2560×1440 @ 60 Hz", "start tv Desktop 2560x1440"), events);

        tv.report(ClientState.AVAILABLE, null);
        awaitMode(ORIGINAL);
        assertFalse(lock.locked, "the desktop's own lock takes over");
    }

    @Test
    void keepsPlacingWindowsWhileReconnecting() throws Exception {
        try (StreamDirector slow = new StreamDirector(registry, new HostResolution(display),
                () -> Optional.ofNullable(sunshineOutput.get()), Duration.ofSeconds(30))) {
            List<String> placed = recordPlacing(slow);
            agent("tv", ClientState.AVAILABLE, null);
            assertTrue(slow.start("tv", "Steam Big Picture").ok());

            assertTrue(slow.restart("tv", "Steam Big Picture", p -> { }).ok());
            Thread.sleep(150);
            assertEquals(List.of("place Steam Big Picture", "place Steam Big Picture"), placed);
        }
    }

    @Test
    void doesNotSendTheClientToStreamWhenSunshineIsNotRunning() throws Exception {
        StreamDirector down = new StreamDirector(registry, new HostResolution(display),
                () -> Optional.ofNullable(sunshineOutput.get()), () -> {
                    throw new java.io.IOException("Sunshine isn't running");
                }, Duration.ofMillis(50));
        agent("tv", ClientState.AVAILABLE, null);

        StreamDirector.StartResult start = down.start("tv", "Desktop");
        assertFalse(start.ok());
        assertEquals("Sunshine isn't running", start.result().error());

        agent("tv2", ClientState.STREAMING, "Desktop");
        StreamDirector.StartResult restart = down.restart("tv2", "Desktop", progress -> { });
        assertFalse(restart.ok());
        assertEquals(List.of(), events, "no command to the client and no monitor change");
    }

    @Test
    void restartAppliesNewResolutionWithoutRestoringInBetween() throws Exception {
        agent("tv", ClientState.AVAILABLE, null);
        settings("tv", 2560, 1440, 60);
        director.start("tv", "Desktop");
        events.clear();

        settings("tv", 1280, 720, 60);
        Thread.sleep(150); // longer than the restore delay
        StreamDirector.StartResult result = director.restart("tv", "Desktop", p -> { });

        assertTrue(result.ok());
        assertEquals(List.of("disconnect tv", "mode DP-2 1280×720 @ 60 Hz", "start tv Desktop 1280x720"), events,
                "no restore to the original mode while reconnecting");
        Thread.sleep(150);
        assertEquals("1280×720 @ 60 Hz", display.current("DP-2"));
    }

    @Test
    void firstStreamKeepsTheModeWhenAnotherJoins() throws Exception {
        agent("tv", ClientState.AVAILABLE, null);
        agent("bedroom", ClientState.AVAILABLE, null);
        settings("tv", 2560, 1440, 60);
        settings("bedroom", 1280, 720, 60);
        director.start("tv", "Desktop");
        events.clear();

        StreamDirector.StartResult joined = director.start("bedroom", "Desktop");

        assertTrue(joined.ok());
        assertEquals("Host monitor kept at DP-2 2560×1440 @ 60 Hz for tv, which leads", joined.displayNote());
        assertEquals(List.of("start bedroom Desktop 1280x720"), events, "no mode change for the second stream");
        assertEquals("tv", director.modeOwner());
    }

    @Test
    void modeFollowsTheNextClientWhenTheFirstStops() throws Exception {
        ScriptedAgent tv = agent("tv", ClientState.AVAILABLE, null);
        ScriptedAgent bedroom = agent("bedroom", ClientState.AVAILABLE, null);
        agent("kitchen", ClientState.AVAILABLE, null);
        settings("tv", 2560, 1440, 60);
        settings("bedroom", 1280, 720, 60);
        settings("kitchen", 3840, 2160, 60);
        director.start("tv", "Desktop");
        director.start("bedroom", "Desktop");
        director.start("kitchen", "Desktop");

        tv.report(ClientState.AVAILABLE, null);
        awaitMode("1280×720 @ 60 Hz");
        assertEquals("bedroom", director.modeOwner(), "the one that has streamed longest of those left");

        bedroom.report(ClientState.AVAILABLE, null);
        awaitMode("3840×2160 @ 60 Hz");
        assertEquals("kitchen", director.modeOwner());
    }

    @Test
    void originalModeComesBackWhenTheLastStreamEnds() throws Exception {
        ScriptedAgent tv = agent("tv", ClientState.AVAILABLE, null);
        ScriptedAgent bedroom = agent("bedroom", ClientState.AVAILABLE, null);
        settings("tv", 2560, 1440, 60);
        settings("bedroom", 1280, 720, 60);
        director.start("tv", "Desktop");
        director.start("bedroom", "Desktop");

        bedroom.report(ClientState.AVAILABLE, null);
        Thread.sleep(150);
        assertEquals("2560×1440 @ 60 Hz", display.current("DP-2"), "the owner still streams");
        tv.report(ClientState.AVAILABLE, null);
        awaitMode(ORIGINAL);
        assertNull(director.modeOwner());
    }

    @Test
    void onlyTheOwnersNewSettingsChangeTheMode() throws Exception {
        agent("tv", ClientState.AVAILABLE, null);
        agent("bedroom", ClientState.AVAILABLE, null);
        settings("tv", 2560, 1440, 60);
        settings("bedroom", 1280, 720, 60);
        director.start("tv", "Desktop");
        director.start("bedroom", "Desktop");

        settings("bedroom", 3840, 2160, 60);
        StreamDirector.StartResult other = director.restart("bedroom", "Desktop", p -> { });
        assertTrue(other.displayNote().startsWith("Host monitor kept"), other.displayNote());
        assertEquals("2560×1440 @ 60 Hz", display.current("DP-2"));

        settings("tv", 1920, 1080, 60);
        director.restart("tv", "Desktop", p -> { });
        assertEquals("1920×1080 @ 60 Hz", display.current("DP-2"));
        assertEquals("tv", director.modeOwner());
    }

    @Test
    void ownerReconnectsFirst() throws Exception {
        agent("tv", ClientState.AVAILABLE, null);
        agent("bedroom", ClientState.AVAILABLE, null);
        director.start("bedroom", "Desktop");
        director.start("tv", "Desktop");
        assertEquals(List.of("bedroom", "tv"), director.streamingClients().stream().map(ClientView::agentId).toList());
    }

    @Test
    void refusesAStreamSunshineHasNoRoomFor() throws Exception {
        agent("tv", ClientState.STREAMING, "Desktop");
        agent("bedroom", ClientState.AVAILABLE, null);
        director.setStreamLimit(() -> 1);

        StreamDirector.StartResult refused = director.start("bedroom", "Desktop");

        assertFalse(refused.ok());
        assertEquals("Sunshine streams to 1 client at a time, and tv is streaming. Stop a stream first, or raise"
                + " \"channels\" in Sunshine's settings.", refused.result().error());
        assertEquals(List.of(), events, "no command to the client and no monitor change");

        director.setStreamLimit(() -> 2);
        assertTrue(director.start("bedroom", "Desktop").ok());
    }

    @Test
    void aSecondStreamCanOnlyJoinTheRunningApp() throws Exception {
        agent("tv", ClientState.STREAMING, "Steam Big Picture");
        agent("bedroom", ClientState.AVAILABLE, null);

        StreamDirector.StartResult refused = director.start("bedroom", "Desktop");

        assertFalse(refused.ok());
        assertEquals("tv is streaming Steam Big Picture, and a second stream can only join it. Stop that stream"
                + " first to start Desktop.", refused.result().error());
        assertEquals(List.of(), events, "no command to the client and no monitor change");
        assertTrue(director.start("bedroom", "Steam Big Picture").ok());
    }

    @Test
    void anAppBeingStartedCountsAsRunning() throws Exception {
        ScriptedAgent tv = agent("tv", ClientState.AVAILABLE, null);
        agent("bedroom", ClientState.AVAILABLE, null);
        CompletableFuture<Void> answer = new CompletableFuture<>();
        tv.startAnswer = answer;
        CompletableFuture<StreamDirector.StartResult> first = director.startAsync("tv", "Steam Big Picture");
        for (int i = 0; i < 100 && events.stream().noneMatch(e -> e.startsWith("start tv")); i++) {
            Thread.sleep(20);
        }

        assertFalse(director.start("bedroom", "Desktop").ok());
        answer.complete(null);
        assertTrue(first.get().ok());
        assertTrue(director.start("bedroom", "Steam Big Picture").ok());
    }

    @Test
    void aFailedStartQuitsTheAppOnlyIfNobodyElseHasIt() throws Exception {
        ScriptedAgent bedroom = agent("bedroom", ClientState.AVAILABLE, null);
        director.start("bedroom", "Desktop");
        assertTrue(bedroom.quitAppOnFailure, "alone, a failed start leaves no app running");

        ScriptedAgent tv = agent("tv", ClientState.AVAILABLE, null);
        director.start("tv", "Desktop");
        assertFalse(tv.quitAppOnFailure, "bedroom streams it");
    }

    @Test
    void aFailedReconnectLeavesTheAppRunning() throws Exception {
        ScriptedAgent tv = agent("tv", ClientState.STREAMING, "Steam Big Picture");
        director.restart("tv", "Steam Big Picture", p -> { });
        assertFalse(tv.quitAppOnFailure, "the user's game may be in it");
        director.reconnect("tv", "Steam Big Picture");
        assertFalse(tv.quitAppOnFailure);
    }

    @Test
    void streamsNotStartedFromOrbitDontLimitTheApp() throws Exception {
        agent("tv", ClientState.STREAMING, null);
        agent("bedroom", ClientState.AVAILABLE, null);
        assertTrue(director.start("bedroom", "Desktop").ok());
    }

    @Test
    void reconnectingAStreamingClientDoesntCountItself() throws Exception {
        agent("tv", ClientState.STREAMING, "Desktop");
        director.setStreamLimit(() -> 1);
        assertTrue(director.restart("tv", "Desktop", p -> { }).ok());
    }

    @Test
    void refusesAClientJustDisabledInSunshine() throws Exception {
        agent("tv", ClientState.AVAILABLE, null);
        AtomicReference<Boolean> enabled = new AtomicReference<>(true);
        registry.setSunshinePairings(List.of(new SunshineClient.PairedClient("tv", "u-1", true)));
        director.setPairingRefresh(() -> registry.setSunshinePairings(
                List.of(new SunshineClient.PairedClient("tv", "u-1", enabled.get()))));

        enabled.set(false);
        StreamDirector.StartResult result = director.start("tv", "Desktop");
        assertFalse(result.ok());
        assertTrue(result.result().error().startsWith("tv is disabled in Sunshine"), result.result().error());
        assertEquals(List.of("check pairing tv"), events,
                "read again before the start, and the agent rechecks rather than waiting for its next check");

        events.clear();
        enabled.set(true);
        assertTrue(director.start("tv", "Desktop").ok());
        assertEquals(List.of("check pairing tv"), events.subList(0, 1));
    }

    @Test
    void anAppOrbitDidntStartIsOnlyReplacedWhenAsked() throws Exception {
        ScriptedAgent tv = agent("tv", ClientState.AVAILABLE, null);
        java.util.concurrent.atomic.AtomicLong running = new java.util.concurrent.atomic.AtomicLong(42);
        director.setRunningApp(running::get);

        StreamDirector.StartResult refused = director.start("tv", "Zwift");
        assertFalse(refused.ok());
        assertEquals("", refused.runningApp(), "Orbit never saw which app has id 42");
        assertTrue(refused.result().error().contains("Starting Zwift would quit it"), refused.result().error());
        assertEquals(List.of(), events);

        running.set(7);
        assertTrue(director.startAsync("tv", "Zwift", true).get().ok(), "asked to replace it");
        assertTrue(tv.quitAppOnFailure, "it was to be quit anyway");
        tv.report(ClientState.AVAILABLE, null);

        // Orbit started Zwift, so it now knows id 7 for it: another Moonlight streaming it can be joined, and the
        // join's failure must not quit it under them.
        events.clear();
        assertTrue(director.start("tv", "Zwift").ok());
        assertFalse(tv.quitAppOnFailure);
        tv.report(ClientState.AVAILABLE, null);
        StreamDirector.StartResult other = director.start("tv", "Desktop");
        assertEquals("Zwift", other.runningApp());
        assertTrue(other.result().error().startsWith("Sunshine is running Zwift that Orbit didn't start"),
                other.result().error());
    }

    @Test
    void learnedAppIdsOutliveARestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path file = dir.resolve("app-ids.json");
        ScriptedAgent tv = agent("tv", ClientState.AVAILABLE, null);
        java.util.concurrent.atomic.AtomicLong running = new java.util.concurrent.atomic.AtomicLong();
        director.setRunningApp(() -> events.isEmpty() ? 0 : running.get());
        director.rememberAppIdsIn(file);
        running.set(7);
        assertTrue(director.start("tv", "Zwift").ok());
        tv.report(ClientState.AVAILABLE, null);
        events.clear();
        running.set(8);
        assertTrue(director.start("tv", "Zwift").ok(), "nothing ran before, so no question");
        assertEquals("{\"apps\":{\"8\":\"Zwift\"}}", java.nio.file.Files.readString(file).replaceAll("\\s", ""),
                "a new id (the app's cover changed) replaces the old one");
        tv.report(ClientState.AVAILABLE, null);

        StreamDirector restarted = new StreamDirector(registry, new HostResolution(display),
                () -> Optional.ofNullable(sunshineOutput.get()), Duration.ofMillis(50));
        restarted.rememberAppIdsIn(file);
        restarted.setRunningApp(() -> 8);
        assertTrue(restarted.start("tv", "Zwift").ok(), "known from the file: joined without asking");
        assertEquals("Zwift", restarted.start("tv", "Desktop").runningApp());
    }

    @Test
    void anAppAnOrbitClientStreamsIsLeftToClaimApp() throws Exception {
        agent("tv", ClientState.STREAMING, "Desktop");
        agent("bedroom", ClientState.AVAILABLE, null);
        director.setRunningApp(() -> 42);
        StreamDirector.StartResult joined = director.start("bedroom", "Desktop");
        assertTrue(joined.ok(), "Orbit knows the app: tv streams it");
        assertNull(joined.runningApp());
    }

    @Test
    void unknownLimitAllowsTheStream() throws Exception {
        agent("tv", ClientState.STREAMING, "Desktop");
        agent("bedroom", ClientState.AVAILABLE, null);
        director.setStreamLimit(() -> {
            throw new java.io.IOException("Sunshine answered 503");
        });
        assertTrue(director.start("bedroom", "Desktop").ok());
    }

    @Test
    void stoppingOneOfTwoStreamsLeavesTheAppRunning() throws Exception {
        ScriptedAgent tv = agent("tv", ClientState.STREAMING, "Desktop");
        agent("bedroom", ClientState.STREAMING, "Desktop");

        assertTrue(director.stop("bedroom").get().ok());
        assertEquals(List.of("disconnect bedroom"), events, "quitting the app would end tv's stream too");
        assertEquals(List.of(), director.othersStreaming("tv"));

        events.clear();
        assertTrue(director.stop("tv").get().ok());
        assertEquals(List.of("stop tv"), events, "the last stream quits the app");
    }

    @Test
    void endsTheStreamOfAClientUnpairedInSunshine() throws Exception {
        agent("tv", ClientState.STREAMING, "Desktop");
        agent("vm", ClientState.STREAMING, "Desktop");
        agent("den", ClientState.STREAMING, "Desktop");   // paired before Orbit, under another name

        assertEquals(List.of("vm"), director.disconnectUnpaired(Set.of("TV", "vm", "moonlight"), Set.of("tv")));
        awaitEvent("disconnect vm");
        assertEquals(List.of("disconnect vm"), events, "tv is still paired; den was never listed by its name");
    }

    @Test
    void restoresAfterFailedStart() throws Exception {
        ScriptedAgent tv = agent("tv", ClientState.AVAILABLE, null);
        tv.failingStarts.set(1);
        settings("tv", 1280, 720, 60);

        StreamDirector.StartResult result = director.start("tv", "Desktop");

        assertEquals("host not ready", result.result().error());
        awaitMode(ORIGINAL);
    }

    @Test
    void unknownSunshineMonitorStillStreams() throws Exception {
        agent("tv", ClientState.AVAILABLE, null);
        sunshineOutput.set(null);
        StreamDirector.StartResult result = director.start("tv", "Desktop");
        assertTrue(result.ok());
        assertTrue(result.displayNote().startsWith("Host monitor unchanged"), result.displayNote());
        assertEquals(ORIGINAL, display.current("DP-2"));
    }

    @Test
    void withoutDisplayControlNothingChanges() throws Exception {
        StreamDirector plain = new StreamDirector(registry, null, Optional::empty, Duration.ofMillis(10));
        agent("tv", ClientState.AVAILABLE, null);
        StreamDirector.StartResult result = plain.start("tv", "Desktop");
        assertTrue(result.ok());
        assertNull(result.displayNote());
    }

    @Test
    void closeRestores() throws Exception {
        agent("tv", ClientState.AVAILABLE, null);
        settings("tv", 1280, 720, 60);
        director.start("tv", "Desktop");
        director.close();
        assertEquals(ORIGINAL, display.current("DP-2"));
    }

    // ---- turning Sunshine's monitor off while idle ----

    private StreamDirector powerSaving() {
        return new StreamDirector(registry, new HostResolution(display),
                () -> Optional.ofNullable(sunshineOutput.get()), () -> { }, Duration.ofMillis(50), Duration.ZERO);
    }

    private void awaitEvent(String event) throws InterruptedException {
        for (int i = 0; i < 100 && !events.contains(event); i++) {
            Thread.sleep(20);
        }
        assertTrue(events.contains(event), "expected " + event + " in " + events);
    }

    @Test
    void streamingMonitorIsOnOnlyWhileStreaming() throws Exception {
        StreamDirector director = powerSaving();
        ScriptedAgent tv = agent("tv", ClientState.AVAILABLE, null);
        settings("tv", 2560, 1440, 60);

        director.setTurnOffWhenIdle(true, Duration.ZERO);
        awaitEvent("disable DP-2");
        assertFalse(display.enabled("DP-2"));
        assertTrue(display.enabled("HDMI-A-4"), "the primary monitor is never touched");

        events.clear();
        StreamDirector.StartResult result = director.start("tv", "Desktop");
        assertTrue(result.ok());
        assertEquals(List.of("enable DP-2", "mode DP-2 2560×1440 @ 60 Hz", "start tv Desktop 2560x1440"), events,
                "on, then the mode, then the stream");
        assertEquals("DP-2 turned on; set to 2560×1440 @ 60 Hz", result.displayNote());

        events.clear();
        tv.report(ClientState.AVAILABLE, null);
        awaitEvent("disable DP-2");
        assertEquals(List.of("mode DP-2 1920×1080 @ 120 Hz", "disable DP-2"), events, "mode back, then off");

        events.clear();
        director.setTurnOffWhenIdle(false, Duration.ZERO);
        awaitEvent("enable DP-2");
        assertTrue(display.enabled("DP-2"), "switching the setting off turns it back on");
    }

    @Test
    void quittingTurnsTheMonitorBackOn() throws Exception {
        StreamDirector director = powerSaving();
        director.setTurnOffWhenIdle(true, Duration.ZERO);
        awaitEvent("disable DP-2");
        director.close();
        assertTrue(display.enabled("DP-2"), "Sunshine must work without Orbit");
    }

    @Test
    void neverTurnsOffThePrimaryMonitor() throws Exception {
        sunshineOutput.set("HDMI-A-4");
        StreamDirector director = powerSaving();
        assertEquals(Optional.of("HDMI-A-4 is the primary monitor"), director.turnOffRefusal());
        director.setTurnOffWhenIdle(true, Duration.ZERO);
        Thread.sleep(200);
        assertTrue(display.enabled("HDMI-A-4"));
        assertFalse(events.stream().anyMatch(e -> e.startsWith("disable")), events.toString());
    }

    @Test
    void reportsThePrimaryMonitor() throws Exception {
        assertEquals(Optional.of("HDMI-A-4"), powerSaving().primaryMonitor());
    }

    @Test
    void monitorSunshineNoLongerUsesIsTurnedBackOn() throws Exception {
        StreamDirector director = powerSaving();
        director.setTurnOffWhenIdle(true, Duration.ZERO);
        awaitEvent("disable DP-2");
        sunshineOutput.set("HDMI-A-4");   // the user picked another monitor in the Monitor menu
        events.clear();
        agent("tv", ClientState.AVAILABLE, null);   // any client update triggers the idle check
        awaitEvent("enable DP-2");
        assertTrue(display.enabled("DP-2"));
    }

    @Test
    void refusesToTurnOffTheOnlyMonitorThatsOn() {
        List<DisplayControl.Output> outputs = List.of(
                new DisplayControl.Output("DP-2", true, false, "a", List.of()),
                new DisplayControl.Output("HDMI-A-4", false, true, "b", List.of()));
        assertEquals(Optional.of("DP-2 is the only monitor that's on"), MonitorPower.refusal("DP-2", outputs));
        assertEquals(Optional.of("DP-3 isn't connected"), MonitorPower.refusal("DP-3", outputs));
    }

    @Test
    void monitorSwitchTurnsTheMonitorOnWhileSunshineRestarts() throws Exception {
        StreamDirector director = powerSaving();
        director.setTurnOffWhenIdle(true, Duration.ZERO);
        awaitEvent("disable DP-2");
        events.clear();

        new MonitorSwitcher(registry, director, Duration.ZERO)
                .switchTo("DP-2", id -> events.add("restart Sunshine on " + id), progress -> { });

        assertEquals(List.of("enable DP-2", "restart Sunshine on DP-2"), events.subList(0, 2),
                "on before Sunshine restarts, so it lists and captures DP-2");
        awaitEvent("disable DP-2");
    }

    @Test
    void nextRunTurnsBackOnWhatACrashedRunLeftOff(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path file = dir.resolve("monitor-power.json");
        StreamDirector crashed = powerSaving();
        crashed.rememberTurnedOffMonitorsIn(file);
        crashed.setTurnOffWhenIdle(true, Duration.ZERO);
        awaitEvent("disable DP-2");
        // Killed here: close() never runs, so DP-2 stays off.

        // Next run, with the setting switched off in the meantime.
        events.clear();
        StreamDirector next = powerSaving();
        next.rememberTurnedOffMonitorsIn(file);
        next.setTurnOffWhenIdle(false, Duration.ZERO);
        awaitEvent("enable DP-2");
        assertTrue(display.enabled("DP-2"));
        // The file is rewritten just after the monitor turns on.
        for (int i = 0; i < 100 && java.nio.file.Files.readString(file).contains("DP-2"); i++) {
            Thread.sleep(20);
        }
        assertFalse(java.nio.file.Files.readString(file).contains("DP-2"), "nothing left to turn back on");
    }

    @Test
    void nextRunWithTheSettingOnLeavesTheMonitorOff(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path file = dir.resolve("monitor-power.json");
        StreamDirector crashed = powerSaving();
        crashed.rememberTurnedOffMonitorsIn(file);
        crashed.setTurnOffWhenIdle(true, Duration.ZERO);
        awaitEvent("disable DP-2");

        events.clear();
        StreamDirector next = powerSaving();
        next.rememberTurnedOffMonitorsIn(file);
        next.setTurnOffWhenIdle(true, Duration.ZERO);
        Thread.sleep(200);
        assertEquals(List.of(), events, "no flicker: it's already off, and this run knows it turned it off");
        next.close();
        assertTrue(display.enabled("DP-2"), "and this run turns it back on when it quits");
    }

    @Test
    void forgetsAMonitorThatWasUnpluggedSince(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path file = dir.resolve("monitor-power.json");
        java.nio.file.Files.writeString(file, "{\"turnedOff\":[\"DP-9\"]}");
        StreamDirector next = powerSaving();
        next.rememberTurnedOffMonitorsIn(file);
        next.setTurnOffWhenIdle(false, Duration.ZERO);
        for (int i = 0; i < 100 && java.nio.file.Files.readString(file).contains("DP-9"); i++) {
            Thread.sleep(20);
        }
        assertFalse(java.nio.file.Files.readString(file).contains("DP-9"), java.nio.file.Files.readString(file));
        assertFalse(events.stream().anyMatch(e -> e.contains("DP-9")), events.toString());
    }
}
