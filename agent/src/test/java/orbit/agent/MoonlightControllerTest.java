package orbit.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import orbit.protocol.StreamSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MoonlightControllerTest {

    private final List<List<String>> launched = new ArrayList<>();

    /** What moonlight-qt logs as its connection's last stage completes: from then on it streams. */
    private static final String CONNECTED = "echo '00:00:05 - SDL Info (0): Starting input stream...';"
            + " echo '00:00:05 - SDL Info (0): done'; echo; ";

    /** Records the moonlight command line but runs {@code shell} instead. */
    private MoonlightController.Launcher fake(String shell) {
        return command -> {
            launched.add(command);
            return new ProcessBuilder("sh", "-c", shell).redirectErrorStream(true).start();
        };
    }

    private MoonlightController controller(String shell, Runnable onEnded) {
        MoonlightController c = new MoonlightController(List.of("moonlight"), fake(shell), onEnded) {
            @Override
            protected java.util.stream.Stream<ProcessHandle> externalStreams() {
                return java.util.stream.Stream.empty();
            }
        };
        c.connectTimeout = Duration.ofMillis(1500);
        return c;
    }

    @Test
    void buildsStreamArguments() {
        MoonlightController c = new MoonlightController(
                List.of("flatpak", "run", "com.moonlight_stream.Moonlight"), fake("true"), () -> { });
        StreamSettings s = new StreamSettings(2560, 1440, 120, 40_000, List.of("--hdr", "--no-vsync"));
        assertEquals(List.of("flatpak", "run", "com.moonlight_stream.Moonlight",
                        "stream", "192.168.1.5", "Steam Big Picture",
                        "--resolution", "2560x1440", "--fps", "120", "--bitrate", "40000", "--hdr", "--no-vsync"),
                c.streamArgs("192.168.1.5", "Steam Big Picture", s));
    }

    @Test
    void passesTheChosenCodecButLeavesAutomaticToMoonlight() {
        MoonlightController c = controller("true", () -> { });
        StreamSettings hevc = new StreamSettings(1920, 1080, 60, 20_000, List.of("--hdr"), "HEVC");
        assertEquals(List.of("moonlight", "stream", "h", "Desktop", "--resolution", "1920x1080", "--fps", "60",
                "--bitrate", "20000", "--video-codec", "HEVC", "--hdr"), c.streamArgs("h", "Desktop", hevc));
        assertFalse(c.streamArgs("h", "Desktop", StreamSettings.DEFAULT).contains("--video-codec"));
    }

    @Test
    void passesTheTogglesAndAudioThatAreSetAndNothingForMoonlightsDefaults() {
        MoonlightController c = controller("true", () -> { });
        StreamSettings s = new StreamSettings(1920, 1080, 60, 20_000, List.of(), null, false, true, "5.1-surround",
                null);
        assertEquals(List.of("moonlight", "stream", "h", "Desktop", "--resolution", "1920x1080", "--fps", "60",
                        "--bitrate", "20000", "--no-vsync", "--frame-pacing", "--audio-config", "5.1-surround"),
                c.streamArgs("h", "Desktop", s));
        assertEquals(10, c.streamArgs("h", "Desktop", StreamSettings.DEFAULT).size());
    }

    @Test
    void buildsOtherArguments() {
        MoonlightController c = controller("true", () -> { });
        assertEquals(List.of("moonlight", "pair", "h", "--pin", "0042"), c.pairArgs("h", "0042"));
        assertEquals(List.of("moonlight", "quit", "h"), c.quitArgs("h"));
        assertEquals(List.of("moonlight", "list", "h"), c.listArgs("h"));
    }

    @Test
    void streamIsStartedOnceMoonlightsConnectionIsUp() throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        MoonlightController c = controller(CONNECTED + "sleep 1", ended::countDown);
        c.startStream("h", "Desktop", StreamSettings.DEFAULT);
        assertTrue(c.isStreaming());
        assertEquals("Desktop", c.currentApp());
        assertThrows(IllegalStateException.class, () -> c.startStream("h", "Desktop", StreamSettings.DEFAULT));

        assertTrue(ended.await(5, TimeUnit.SECONDS));
        assertFalse(c.isStreaming());
    }

    @Test
    void closesMoonlightThatStaysOpenAfterItsConnectionEnded() throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        // Like moonlight-qt after Sunshine restarts: logs the termination, then shows an error and waits.
        MoonlightController c = controller(CONNECTED + "sleep 0.5; echo 'Connection terminated: -1'; sleep 30",
                ended::countDown);
        c.terminationGrace = Duration.ofMillis(200);
        c.startStream("h", "Desktop", StreamSettings.DEFAULT);
        assertTrue(c.isStreaming());

        assertTrue(ended.await(5, TimeUnit.SECONDS), "moonlight was not closed");
        assertFalse(c.isStreaming());
        assertTrue(launched.stream().noneMatch(cmd -> cmd.contains("quit")), "must not run moonlight quit: " + launched);
    }

    @Test
    void moonlightThatExitsAfterANormalEndIsLeftToExit() throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        MoonlightController c = controller(CONNECTED + "sleep 0.5; echo 'Connection terminated: 0'; sleep 0.1",
                ended::countDown);
        c.terminationGrace = Duration.ofMillis(200);
        c.startStream("h", "Desktop", StreamSettings.DEFAULT);
        assertTrue(ended.await(5, TimeUnit.SECONDS));
        assertFalse(c.isStreaming());
    }

    @Test
    void otherOutputDoesNotEndTheStream() throws Exception {
        MoonlightController c = controller(CONNECTED + "sleep 0.4; echo 'Connection established'; sleep 30",
                () -> { });
        c.terminationGrace = Duration.ofMillis(100);
        c.startStream("h", "Desktop", StreamSettings.DEFAULT);
        Thread.sleep(600);
        assertTrue(c.isStreaming());
        c.disconnect();
    }

    @Test
    void waitsForTheLastConnectionStage() throws Exception {
        MoonlightController c = controller("echo '00:00:01 - SDL Info (0): Starting RTSP handshake...';"
                + " echo '00:00:01 - SDL Info (0): done'; sleep 0.5; " + CONNECTED + "sleep 30", () -> { });
        long start = System.nanoTime();
        c.startStream("h", "Desktop", StreamSettings.DEFAULT);
        assertTrue(System.nanoTime() - start > 400_000_000L, "an earlier stage's done isn't the stream starting");
        assertTrue(c.isStreaming());
        c.disconnect();
    }

    @Test
    void aSlowLaunchHasLongerOnceTheSessionStarted() throws Exception {
        // Sunshine runs the app's prep commands before it answers Moonlight's launch request.
        MoonlightController c = controller("echo '00:00:01 - SDL Info (0): Checking for hardware decoders';"
                + " sleep 1; " + CONNECTED + "sleep 30", () -> { });
        c.connectTimeout = Duration.ofMillis(300);
        c.sessionTimeout = Duration.ofSeconds(5);
        c.startStream("h", "Desktop", StreamSettings.DEFAULT);
        assertTrue(c.isStreaming());
        c.disconnect();
    }

    @Test
    void theLastStagesDoneCountsAfterOtherLines() throws Exception {
        MoonlightController c = controller("echo '00:00:05 - SDL Info (0): Starting input stream...';"
                + " echo '00:00:05 - SDL Info (0): Initializing audio renderer...';"
                + " echo '00:00:05 - SDL Info (0): done'; sleep 30", () -> { });
        c.startStream("h", "Desktop", StreamSettings.DEFAULT);
        assertTrue(c.isStreaming());
        c.disconnect();
    }

    @Test
    void aFailedConnectionStageFailsTheStartAndClosesMoonlight() throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        // moonlight-qt then shows the error in a dialog and waits for someone to close it.
        MoonlightController c = controller("echo '00:00:02 - SDL Info (0): Starting control stream...';"
                + " echo '00:00:09 - SDL Info (0): failed: 10060'; echo; sleep 30", ended::countDown);
        IOException e = assertThrows(IOException.class, () -> c.startStream("h", "Desktop", StreamSettings.DEFAULT));
        assertEquals("Moonlight: Starting control stream failed (error 10060)", e.getMessage());
        assertTrue(ended.await(5, TimeUnit.SECONDS), "moonlight was not closed");
        assertFalse(c.isStreaming());
    }

    @Test
    void aStartThatNeverConnectsTimesOutWithMoonlightsLastWarning() throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        // moonlight-qt looking for a host it can't reach, as on a Windows VM: it says so only on screen, after 30 s.
        MoonlightController c = controller("echo '00:00:07 - Qt Warning: \"serverinfo\" request failed with error:"
                + " QNetworkReply::ConnectionRefusedError'; sleep 30", ended::countDown);
        c.connectTimeout = Duration.ofMillis(500);
        IOException e = assertThrows(IOException.class, () -> c.startStream("h", "Desktop", StreamSettings.DEFAULT));
        assertEquals("Moonlight didn't connect to h within 0 s (it didn't get as far as starting a session); last"
                + " problem: \"serverinfo\" request failed with error: QNetworkReply::ConnectionRefusedError",
                e.getMessage());
        assertTrue(ended.await(5, TimeUnit.SECONDS), "moonlight was not closed");
        assertFalse(c.isStreaming());
    }

    /** Like {@link #controller}, but {@code moonlight quit} succeeds at once. */
    private MoonlightController quittable(String streamShell) {
        MoonlightController.Launcher launcher = command -> {
            launched.add(command);
            String shell = command.contains("quit") ? "true" : streamShell;
            return new ProcessBuilder("sh", "-c", shell).redirectErrorStream(true).start();
        };
        MoonlightController c = new MoonlightController(List.of("moonlight"), launcher, () -> { }) {
            @Override
            protected java.util.stream.Stream<ProcessHandle> externalStreams() {
                return java.util.stream.Stream.empty();
            }
        };
        c.connectTimeout = Duration.ofMillis(500);
        return c;
    }

    @Test
    void aStartThatFailsAfterTheSessionBeganQuitsTheAppWhenAsked() {
        // Moonlight launched the app, then couldn't connect (e.g. UDP blocked): the app would keep running.
        MoonlightController c = quittable("echo '00:00:02 - SDL Info (0): Starting control stream...';"
                + " echo '00:00:09 - SDL Info (0): failed: 10060'; sleep 30");
        IOException e = assertThrows(IOException.class,
                () -> c.startStream("h", "Desktop", StreamSettings.DEFAULT, true));
        assertEquals("Moonlight: Starting control stream failed (error 10060); quit Desktop on the host",
                e.getMessage());
        assertEquals(List.of("moonlight", "quit", "h"), launched.getLast());
    }

    @Test
    void aStartThatNeverReachedTheSessionQuitsNothing() {
        MoonlightController c = quittable("echo '00:00:07 - Qt Warning: \"serverinfo\" request failed'; sleep 30");
        assertThrows(IOException.class, () -> c.startStream("h", "Desktop", StreamSettings.DEFAULT, true));
        assertTrue(launched.stream().noneMatch(cmd -> cmd.contains("quit")), "nothing was launched: " + launched);
    }

    @Test
    void aFailedStartQuitsNothingUnlessAsked() {
        MoonlightController c = quittable("echo '00:00:02 - SDL Info (0): Starting control stream...';"
                + " echo '00:00:09 - SDL Info (0): failed: 10060'; sleep 30");
        assertThrows(IOException.class, () -> c.startStream("h", "Desktop", StreamSettings.DEFAULT, false));
        assertTrue(launched.stream().noneMatch(cmd -> cmd.contains("quit")), "another client streams it: " + launched);
    }

    @Test
    void immediateExitReportsOutput() {
        MoonlightController c = controller("echo 'App not found'; exit 1", () -> { });
        IOException e = assertThrows(IOException.class, () -> c.startStream("h", "Nope", StreamSettings.DEFAULT));
        assertTrue(e.getMessage().contains("App not found"), e.getMessage());
    }

    @Test
    void pairedFollowsListExitCode() throws Exception {
        assertTrue(controller("exit 0", () -> { }).isPaired("h"));
        assertFalse(controller("echo 'Computer h has not been paired. Please open Moonlight to pair before"
                + " retrieving games list.' >&2; exit 255", () -> { }).isPaired("h"));
        assertEquals(List.of("moonlight", "list", "h"), launched.getLast());
    }

    @Test
    void disconnectEndsStreamWithoutQuittingTheApp() throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        MoonlightController c = controller(CONNECTED + "sleep 30", ended::countDown);
        c.startStream("h", "Desktop", StreamSettings.DEFAULT);
        assertTrue(c.isStreaming());

        c.disconnect();

        assertTrue(ended.await(5, TimeUnit.SECONDS));
        assertFalse(c.isStreaming());
        assertTrue(launched.stream().noneMatch(cmd -> cmd.contains("quit")), "must not run moonlight quit: " + launched);
    }

    @Test
    void consoleLauncherKeepsMoonlightsQtScreensOffTheDisplay() throws Exception {
        Process p = MoonlightController.consoleLauncher().start(List.of("sh", "-c", "echo $QT_QPA_PLATFORM"));
        assertEquals("offscreen", new String(p.getInputStream().readAllBytes()).strip());
    }

    @Test
    void stopWithoutAStreamFailsWhenQuitFails() {
        MoonlightController c = controller("echo 'Failed to connect'; exit 1", () -> { });
        IOException e = assertThrows(IOException.class, () -> c.stopStream("h"));
        assertTrue(e.getMessage().contains("Failed to connect"), e.getMessage());
    }

    /**
     * Fake Moonlight for pairing: {@code list} succeeds (paired) from call number {@code pairedFromList} on, and
     * {@code pair} runs {@code pairShell}.
     */
    private MoonlightController pairing(int pairedFromList, String pairShell) {
        AtomicInteger listCalls = new AtomicInteger();
        MoonlightController.Launcher launcher = command -> {
            launched.add(command);
            String shell = switch (command.get(1)) {
                case "list" -> listCalls.incrementAndGet() >= pairedFromList ? "exit 0" : "echo not paired; exit 1";
                case "pair" -> pairShell;
                default -> "exit 0";
            };
            return new ProcessBuilder("sh", "-c", shell).redirectErrorStream(true).start();
        };
        MoonlightController c = new MoonlightController(List.of("moonlight"), launcher, () -> { });
        c.pairTimeout = Duration.ofSeconds(2);
        return c;
    }

    private long pairCommands() {
        return launched.stream().filter(cmd -> cmd.get(1).equals("pair")).count();
    }

    @Test
    void alreadyPairedSkipsMoonlightPair() throws Exception {
        AtomicInteger pinsSent = new AtomicInteger();
        MoonlightController c = pairing(1, "sleep 30");
        assertEquals(MoonlightController.PairOutcome.ALREADY_PAIRED, c.pair("h", "1234", pinsSent::incrementAndGet, new java.util.concurrent.CompletableFuture<>()));
        assertEquals(0, pairCommands(), "must not start moonlight pair");
        assertEquals(0, pinsSent.get());
    }

    @Test
    void hangingAlreadyPairedWindowIsClosed() throws Exception {
        // First list says not paired (stale), then Moonlight's pair shows "already paired" and waits forever.
        MoonlightController c = pairing(2, "echo 'livingroom is already paired'; sleep 30");
        long start = System.nanoTime();
        assertEquals(MoonlightController.PairOutcome.ALREADY_PAIRED, c.pair("h", "1234", () -> { }, new java.util.concurrent.CompletableFuture<>()));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 2, "must not wait for the timeout");
    }

    @Test
    void closingMoonlightEndsWhatItStarted(@TempDir Path dir) throws Exception {
        // A wrapper (a script in moonlightCommand, flatpak run) whose child is Moonlight itself.
        Path child = dir.resolve("child.pid");
        MoonlightController c = pairing(2, "echo 'livingroom is already paired'; sleep 30 & echo $! > " + child
                + "; wait");
        long start = System.nanoTime();
        assertEquals(MoonlightController.PairOutcome.ALREADY_PAIRED, c.pair("h", "1234", () -> { },
                new java.util.concurrent.CompletableFuture<>()));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 2, "must not wait for the child");
        long pid = Long.parseLong(Files.readString(child).strip());
        Optional<ProcessHandle> left = ProcessHandle.of(pid);
        if (left.isPresent()) {
            left.get().onExit().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void pairsAndSendsPin() throws Exception {
        AtomicInteger pinsSent = new AtomicInteger();
        MoonlightController c = pairing(2, "exit 0");
        assertEquals(MoonlightController.PairOutcome.PAIRED, c.pair("h", "1234", pinsSent::incrementAndGet, new java.util.concurrent.CompletableFuture<>()));
        assertEquals(1, pinsSent.get());
        assertTrue(launched.contains(List.of("moonlight", "pair", "h", "--pin", "1234")));
    }

    @Test
    void successWindowIsClosedOnSunshinesVerdict() throws Exception {
        // Like moonlight-qt: pairing succeeds, then "Successfully paired" stays on screen until someone closes it.
        MoonlightController c = pairing(2, "sleep 30");
        c.pairSaveGrace = Duration.ofMillis(100);
        var verdict = new java.util.concurrent.CompletableFuture<Boolean>();
        long start = System.nanoTime();
        assertEquals(MoonlightController.PairOutcome.PAIRED, c.pair("h", "1234", () -> verdict.complete(true), verdict));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1500, "must not wait for the timeout");
    }

    @Test
    void rejectedPinClosesTheWindowAndFails() {
        MoonlightController c = pairing(Integer.MAX_VALUE, "sleep 30");
        var verdict = new java.util.concurrent.CompletableFuture<Boolean>();
        long start = System.nanoTime();
        IOException e = assertThrows(IOException.class, () -> c.pair("h", "1234", () -> verdict.complete(false), verdict));
        assertTrue(e.getMessage().contains("did not accept the PIN"), e.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1500, "must not wait for the timeout");
    }

    @Test
    void failedPairingReportsOutput() {
        MoonlightController c = pairing(Integer.MAX_VALUE, "echo 'wrong PIN'; exit 1");
        IOException e = assertThrows(IOException.class, () -> c.pair("h", "1234", () -> { }, new java.util.concurrent.CompletableFuture<>()));
        assertTrue(e.getMessage().contains("wrong PIN"), e.getMessage());
    }

    @Test
    void stuckPairingTimesOut() {
        MoonlightController c = pairing(Integer.MAX_VALUE, "sleep 30");
        IOException e = assertThrows(IOException.class, () -> c.pair("h", "1234", () -> { }, new java.util.concurrent.CompletableFuture<>()));
        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
    }

    @Test
    void looksForStreamsStartedOutsideOrbitAtMostOnceAWhile() throws Exception {
        java.util.concurrent.atomic.AtomicInteger looks = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean outside = new java.util.concurrent.atomic.AtomicBoolean();
        MoonlightController c = new MoonlightController(List.of("moonlight"), fake("true"), () -> { }) {
            @Override
            protected java.util.stream.Stream<ProcessHandle> externalStreams() {
                looks.incrementAndGet();
                return outside.get() ? java.util.stream.Stream.of(ProcessHandle.current())
                        : java.util.stream.Stream.empty();
            }
        };
        c.externalCheckAge = Duration.ofMillis(300);
        assertFalse(c.isStreaming());
        outside.set(true);
        assertFalse(c.isStreaming(), "the last look still counts");
        assertEquals(1, looks.get());
        Thread.sleep(400);
        assertTrue(c.isStreaming());
        assertEquals(2, looks.get());
    }

    @Test
    void stopOwnStreamLeavesStreamsStartedOutsideOrbit() throws Exception {
        Process outside = new ProcessBuilder("sleep", "30").start();
        java.util.concurrent.atomic.AtomicBoolean outsideRunning = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            MoonlightController c = new MoonlightController(List.of("moonlight"), fake(CONNECTED + "sleep 30"),
                    () -> { }) {
                @Override
                protected java.util.stream.Stream<ProcessHandle> externalStreams() {
                    return outsideRunning.get() ? java.util.stream.Stream.of(outside.toHandle())
                            : java.util.stream.Stream.empty();
                }
            };
            c.connectTimeout = Duration.ofMillis(1500);
            assertFalse(c.stopOwnStream(), "nothing to stop yet");

            c.startStream("h", "Desktop", StreamSettings.DEFAULT);
            outsideRunning.set(true); // someone also started a stream by hand
            assertTrue(c.stopOwnStream());

            assertEquals(null, c.currentApp());
            assertTrue(outside.isAlive(), "a stream started by hand must keep running");
            assertTrue(launched.stream().noneMatch(cmd -> cmd.contains("quit")));
        } finally {
            outside.destroyForcibly();
        }
    }

    @Test
    void closesStreamsLeftOverFromADeadAgent() throws Exception {
        Process leftover = new ProcessBuilder("sleep", "30").start();
        try {
            MoonlightController c = new MoonlightController(List.of("moonlight"), fake("true"), () -> { }) {
                @Override
                protected java.util.stream.Stream<ProcessHandle> externalStreams() {
                    return leftover.isAlive() ? java.util.stream.Stream.of(leftover.toHandle())
                            : java.util.stream.Stream.empty();
                }
            };
            assertEquals(1, c.closeLeftoverStreams());
            assertTrue(leftover.waitFor(5, java.util.concurrent.TimeUnit.SECONDS), "the leftover stream is closed");
            assertEquals(0, c.closeLeftoverStreams());
        } finally {
            leftover.destroyForcibly();
        }
    }

    @Test
    void tellsNotPairedApartFromUnreachable() {
        assertEquals(MoonlightController.Pairing.Kind.PAIRED,
                MoonlightController.classify(new MoonlightController.Outcome(true, "Desktop\nSteam")).kind());
        assertEquals(MoonlightController.Pairing.Kind.NOT_PAIRED, MoonlightController.classify(
                new MoonlightController.Outcome(false, "Computer gamingpc has not been paired. Please open Moonlight"
                        + " to pair before retrieving games list.")).kind());
        MoonlightController.Pairing unreachable = MoonlightController.classify(
                new MoonlightController.Outcome(false, "Failed to connect to gamingpc"));
        assertEquals(MoonlightController.Pairing.Kind.UNREACHABLE, unreachable.kind());
        assertEquals("Failed to connect to gamingpc", unreachable.detail());
        MoonlightController.Pairing noAnswer = MoonlightController.classify(
                new MoonlightController.Outcome(false, "timed out after 20s"));
        assertEquals(MoonlightController.Pairing.Kind.NO_ANSWER, noAnswer.kind(), "the agent gave up first");
        assertEquals("moonlight list timed out after 20s", noAnswer.detail());
        MoonlightController.Pairing failed = MoonlightController.classify(
                new MoonlightController.Outcome(false, "Qt: something\nSSL handshake failed\n"));
        assertEquals(MoonlightController.Pairing.Kind.FAILED, failed.kind());
        assertEquals("SSL handshake failed", failed.detail(), "Moonlight's last words");
    }
}
