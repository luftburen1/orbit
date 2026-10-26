package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises {@link SunshineClient} against a fake Sunshine API served over plain HTTP. */
class SunshineClientTest {

    private HttpServer server;
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> auth = new CopyOnWriteArrayList<>();
    private final AtomicInteger pinCalls = new AtomicInteger();
    private volatile int pinAcceptedOnCall = 1;
    /** Waiting pairing requests as new Sunshine lists them at GET /api/pin; null for old Sunshine (web page). */
    private volatile String pendingPairings;
    /** GETs of /api/pin before the requests above show up, as when Moonlight hasn't reached Sunshine yet. */
    private volatile int pendingVisibleAfterGets;
    private final AtomicInteger pendingGets = new AtomicInteger();
    private volatile String postedPin;

    /** Monitor list as this host's Sunshine logs it (KMS capture). */
    static final String LOG = """
            [2026-10-10 21:03:33.101]: Info: Sunshine version: v2026.914.233613
            [2026-10-10 21:03:33.500]: Info: [wayland] Found monitor: Acme Corp. TV-55
            [2026-10-10 21:03:33.502]: Info: -------- Start of KMS monitor list --------
            [2026-10-10 21:03:33.502]: Info: Monitor 1 is DP-2: Acme Corp. TV-55
            [2026-10-10 21:03:33.502]: Info: Monitor 0 is HDMI-A-4: Acme Corp. UW34
            [2026-10-10 21:03:33.502]: Info: --------- End of KMS monitor list ---------
            [2026-10-10 21:03:33.600]: Info: Screencasting with KMS
            """;

    // Fake Sunshine state for the config/restart endpoints.
    private volatile String log = LOG;
    private volatile String config = """
            {"adapter_name":"/dev/dri/renderD128","capture":"kms","encoder":"vaapi","max_bitrate":"20000",
             "output_name":"DP-2","platform":"linux","status":true,"version":"2026.914.233613"}
            """;
    private volatile String postedConfig;
    private volatile boolean restartWorks = true;
    /** False: the restarted Sunshine's log starts like the old one, as a version with another log format might. */
    private volatile boolean newLogOnRestart = true;
    private volatile long downUntil;
    private final AtomicInteger restarts = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
        bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String body;
        int code = 200;
        if (!"Basic dXNlcjpwYXNz".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            code = 401;
            body = "";
        } else if (path.equals("/api/logs") || path.equals("/api/config")) {
            if (System.currentTimeMillis() < downUntil) {
                code = 503;
                body = "";
            } else if (path.equals("/api/logs")) {
                body = log;
            } else if (exchange.getRequestMethod().equals("POST")) {
                postedConfig = bodies.getLast();
                body = "{\"status\":true}";
            } else {
                body = config;
            }
        } else if (path.equals("/api/restart")) {
            restarts.incrementAndGet();
            if (restartWorks) {
                downUntil = System.currentTimeMillis() + 100;
                log = !newLogOnRestart ? log : "[2026-10-10 23:59:00.000]: Info: Sunshine version: v2026.914.233613\n" + LOG.substring(LOG.indexOf('\n') + 1);
            }
            body = "{\"status\":true}";
        } else if (path.equals("/api/pin") && exchange.getRequestMethod().equals("GET")) {
            if (pendingPairings == null) {
                body = "<!DOCTYPE html><html><body>Sunshine</body></html>";   // old Sunshine: its web page
            } else {
                body = pendingGets.incrementAndGet() > pendingVisibleAfterGets ? pendingPairings : "{\"pairings\":[]}";
            }
        } else if (path.equals("/api/pin")) {
            postedPin = bodies.getLast();
            if (pendingPairings != null && !postedPin.contains("pairing_id")) {
                code = 400;
                body = "{\"status_code\":400,\"status\":false,"
                        + "\"error\":\"pairing_id must contain exactly 32 hexadecimal characters\"}";
            } else {
                body = pinCalls.incrementAndGet() >= pinAcceptedOnCall ? "{\"status\":\"true\"}" : "{\"status\":false}";
            }
        } else {
            body = responses.getOrDefault(path, "{}");
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private SunshineClient client(String password) {
        return new SunshineClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "user", password, Duration.ofMillis(10), Duration.ofMillis(20), Duration.ofSeconds(2));
    }

    @Test
    void submitsPinWithName() throws Exception {
        client("pass").submitPin("1234", "livingroom").get(5, TimeUnit.SECONDS);
        assertEquals("Basic dXNlcjpwYXNz", auth.getFirst());
        assertTrue(postedPin.contains("\"pin\":\"1234\""), postedPin);
        assertTrue(postedPin.contains("\"name\":\"livingroom\""), postedPin);
        assertFalse(postedPin.contains("pairing_id"), "old Sunshine has no pairing list, so no id: " + postedPin);
    }

    @Test
    void retriesPinUntilSunshineIsReady() throws Exception {
        pinAcceptedOnCall = 3;
        client("pass").submitPin("1234", "x").get(5, TimeUnit.SECONDS);
        assertEquals(3, pinCalls.get());
    }

    private static final String TWO_WAITING = """
            {"pairings":[
              {"id":"0123456789abcdef0123456789abcdef","name":"laptop","address":"192.168.1.77"},
              {"id":"fedcba9876543210fedcba9876543210","name":"laptop","address":"::ffff:192.168.1.50"}]}
            """;

    @Test
    void newSunshineGetsThePinForTheRequestFromTheClientsAddress() throws Exception {
        pendingPairings = TWO_WAITING;
        client("pass").submitPin("1234", "livingroom", "192.168.1.50").get(5, TimeUnit.SECONDS);
        assertTrue(postedPin.contains("\"pairing_id\":\"fedcba9876543210fedcba9876543210\""), postedPin);
    }

    @Test
    void newSunshineWithOneWaitingRequestNeedsNoAddress() throws Exception {
        pendingPairings = "{\"pairings\":[{\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"laptop\","
                + "\"address\":\"10.0.0.9\"}]}";
        client("pass").submitPin("1234", "livingroom", null).get(5, TimeUnit.SECONDS);
        assertTrue(postedPin.contains("0123456789abcdef0123456789abcdef"), postedPin);
    }

    @Test
    void waitsUntilTheClientsRequestReachesSunshine() throws Exception {
        pendingPairings = TWO_WAITING;
        pendingVisibleAfterGets = 2;
        client("pass").submitPin("1234", "livingroom", "192.168.1.50").get(5, TimeUnit.SECONDS);
        assertEquals(3, pendingGets.get(), "asked again until it showed up");
        assertEquals(1, pinCalls.get(), "and only then entered the PIN");
    }

    @Test
    void readsTheRunningAppsIdFromServerinfo() {
        assertEquals(1605097642, SunshineClient.currentGame("<?xml version=\"1.0\" encoding=\"utf-8\"?><root"
                + " status_code=\"200\"><PairStatus>0</PairStatus><currentgame>1605097642</currentgame>"
                + "<state>SUNSHINE_SERVER_BUSY</state></root>"));
        assertEquals(0, SunshineClient.currentGame("<root><currentgame>0</currentgame></root>"));
        assertEquals(0, SunshineClient.currentGame("<root/>"));
    }

    @Test
    void pairsAWaitingRequestByItsId() throws Exception {
        pendingPairings = TWO_WAITING;
        SunshineClient c = client("pass");
        List<SunshineClient.PendingPairing> waiting = c.pendingPairings().orElseThrow();
        assertEquals(new SunshineClient.PendingPairing("0123456789abcdef0123456789abcdef", "laptop", "192.168.1.77"),
                waiting.getFirst());
        c.pair(waiting.getFirst(), "4321", "phone");
        assertTrue(postedPin.contains("\"pairing_id\":\"0123456789abcdef0123456789abcdef\""), postedPin);
        assertTrue(postedPin.contains("\"name\":\"phone\""), postedPin);
        assertEquals(1, pinCalls.get());

        pinAcceptedOnCall = 5;
        var e = assertThrows(SunshineClient.SunshineException.class, () -> c.pair(waiting.getFirst(), "0000", "phone"));
        assertTrue(e.getMessage().contains("didn't accept the PIN"), e.getMessage());
        assertEquals(2, pinCalls.get(), "a wrong PIN isn't retried");
    }

    @Test
    void refusesToGuessAmongSeveralRequestsFromOtherAddresses() {
        pendingPairings = TWO_WAITING;
        var e = assertThrows(ExecutionException.class,
                () -> client("pass").submitPin("1234", "livingroom", "192.168.1.99").get(5, TimeUnit.SECONDS));
        assertTrue(e.getCause().getMessage().contains("several clients are waiting to pair"), e.getCause().getMessage());
    }

    @Test
    void errorsCarrySunshinesOwnReason() throws Exception {
        pendingPairings = "{\"pairings\":[]}";
        var c = new SunshineClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "user", "pass");
        // A PIN posted the old way to new Sunshine: the 400 now says why.
        var method = SunshineClient.class.getDeclaredMethod("post", String.class, Object.class);
        method.setAccessible(true);
        var e = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(c, "/api/pin", java.util.Map.of("pin", "1234", "name", "x")));
        assertEquals("Sunshine answered HTTP 400 for /api/pin: pairing_id must contain exactly 32 hexadecimal characters",
                e.getCause().getMessage());
    }

    @Test
    void givesUpOnPinEventually() {
        pinAcceptedOnCall = Integer.MAX_VALUE;
        var e = assertThrows(ExecutionException.class,
                () -> client("pass").submitPin("1234", "x").get(5, TimeUnit.SECONDS));
        assertInstanceOf(SunshineClient.SunshineException.class, e.getCause());
        assertEquals(SunshineClient.PIN_ATTEMPTS, pinCalls.get());
    }

    @Test
    void badCredentialsAreReported() {
        var e = assertThrows(ExecutionException.class,
                () -> client("wrong").submitPin("1234", "x").get(5, TimeUnit.SECONDS));
        assertTrue(e.getCause().getMessage().contains("username or password"), e.getCause().getMessage());
    }

    @Test
    void findsAppCovers(@TempDir Path dir) throws Exception {
        Path assets = Files.createDirectories(dir.resolve("assets"));
        Files.writeString(assets.resolve("steam.png"), "png");
        Path own = Files.writeString(dir.resolve("zwift.png"), "png");
        List<Path> folders = List.of(dir.resolve("missing"), assets);

        assertEquals(Optional.of(assets.resolve("steam.png")),
                new SunshineClient.App("Steam Big Picture", true, "steam.png").cover(folders), "Sunshine's own");
        assertEquals(Optional.of(own), new SunshineClient.App("Zwift", true, own.toString()).cover(folders));
        assertEquals(Optional.empty(), new SunshineClient.App("Gone", true, dir + "/gone.png").cover(folders));
        assertEquals(Optional.empty(), new SunshineClient.App("Kodi", true, "").cover(folders));
    }

    @Test
    void sunshinesCoverFolderDependsOnTheOs() {
        assertEquals(Path.of("/usr/share/sunshine"), SunshineClient.App.assetFolders("Linux", Map.of()).getFirst());
        assertEquals(Path.of("D:\\Apps", "Sunshine", "assets"),
                SunshineClient.App.assetFolders("Windows 11", Map.of("ProgramFiles", "D:\\Apps")).getFirst());
        assertEquals(Path.of("/opt/homebrew/share/sunshine"),
                SunshineClient.App.assetFolders("Mac OS X", Map.of()).getFirst());
    }

    @Test
    void listsAppsAndClients() throws Exception {
        responses.put("/api/apps", """
                {"env":{},"apps":[{"name":"Desktop","output":"","image-path":"desktop.png"},
                 {"name":"Steam Big Picture","detached":["setsid steam steam://open/bigpicture"]},
                 {"name":"Kodi","cmd":"kodi"},{"name":"Nothing detached","detached":[]}]}
                """);
        responses.put("/api/clients/list", """
                {"named_certs":[{"name":"livingroom","uuid":"u-1"},{"enabled":false,"name":"phone","uuid":"u-2"}],"status":true}
                """);
        SunshineClient c = client("pass");
        assertEquals(List.of(new SunshineClient.App("Desktop", false, "desktop.png"),
                new SunshineClient.App("Steam Big Picture", true, ""), new SunshineClient.App("Kodi", true, ""),
                new SunshineClient.App("Nothing detached", false, "")), c.listApps());
        assertEquals(List.of(new SunshineClient.PairedClient("livingroom", "u-1", true),
                new SunshineClient.PairedClient("phone", "u-2", false)), c.listClients(), "enabled unless it says not");
    }

    @Test
    void unpairChecksStatus() throws Exception {
        responses.put("/api/clients/unpair", "{\"status\":true}");
        client("pass").unpair("u-1");
        assertTrue(bodies.getLast().contains("u-1"));

        responses.put("/api/clients/unpair", "{\"status\":false}");
        assertThrows(SunshineClient.SunshineException.class, () -> client("pass").unpair("u-1"));
    }

    @Test
    void parsesKmsMonitorList() {
        assertEquals(List.of(
                        new SunshineClient.Monitor("DP-2", 1, "Acme Corp. TV-55"),
                        new SunshineClient.Monitor("HDMI-A-4", 0, "Acme Corp. UW34")),
                SunshineClient.parseMonitors(LOG));
    }

    @Test
    void lastMonitorListWins() {
        String later = """
                Info: -------- Start of KMS monitor list --------
                Info: Monitor 0 is HDMI-A-1: Some TV
                Info: --------- End of KMS monitor list ---------
                """;
        assertEquals(List.of(new SunshineClient.Monitor("HDMI-A-1", 0, "Some TV")),
                SunshineClient.parseMonitors(LOG + later));
    }

    @Test
    void noMonitorListMeansEmpty() {
        assertTrue(SunshineClient.parseMonitors("Info: Screencasting with portal\n").isEmpty());
        assertTrue(SunshineClient.parseMonitors("Info: -------- Start of KMS monitor list --------\n").isEmpty(),
                "unterminated list is ignored");
    }

    @Test
    void readsTheEncodersOfSunshinesLastEncoderTest() {
        String log = """
                [2026-10-13 21:58:16.110]: Error: Could not open codec [av1_vaapi]: Function not implemented
                [2026-10-13 21:58:16.316]: Info: // Testing for available encoders, this may generate errors. //
                [2026-10-13 21:58:16.316]: Info: Found H.264 encoder: h264_vaapi [vaapi]
                [2026-10-13 21:58:16.316]: Info: Found HEVC encoder: hevc_vaapi [vaapi]
                [2026-10-13 21:58:36.274]: Info: // Testing for available encoders, this may generate errors. //
                [2026-10-13 21:58:36.274]: Info: Found H.264 encoder: h264_vaapi [vaapi]
                [2026-10-13 21:58:36.274]: Info: Found AV1 encoder: av1_vaapi [vaapi]
                [2026-10-13 21:58:36.440]: Info: Creating encoder [hevc_vaapi]
                """;
        assertEquals(java.util.Optional.of(List.of("H.264", "AV1")), SunshineClient.parseEncoders(log));
        assertTrue(SunshineClient.parseEncoders("Info: Creating encoder [hevc_vaapi]\n").isEmpty());
    }

    @Test
    void readsMonitorsAndCurrentChoice() throws Exception {
        SunshineClient c = client("pass");
        assertEquals(2, c.listMonitors().size());
        assertEquals("DP-2", c.currentMonitor());
    }

    @Test
    void selectMonitorRewritesConfigAndWaitsForRestart() throws Exception {
        client("pass").selectMonitor("HDMI-A-4");

        assertEquals(1, restarts.get());
        var posted = orbit.protocol.MessageCodec.newMapper().readTree(postedConfig);
        assertEquals("HDMI-A-4", posted.path("output_name").asText());
        assertEquals("kms", posted.path("capture").asText());
        assertEquals("20000", posted.path("max_bitrate").asText());
        assertEquals("/dev/dri/renderD128", posted.path("adapter_name").asText());
        for (String key : List.of("status", "platform", "version")) {
            assertTrue(posted.path(key).isMissingNode(), key + " must not be written back");
        }
    }

    @Test
    void automaticRemovesOutputName() throws Exception {
        client("pass").selectMonitor("");
        var posted = orbit.protocol.MessageCodec.newMapper().readTree(postedConfig);
        assertTrue(posted.path("output_name").isMissingNode());
        assertEquals("vaapi", posted.path("encoder").asText());
    }

    @Test
    void restartIsNoticedEvenIfTheLogStartsTheSame() throws Exception {
        newLogOnRestart = false;
        client("pass").selectMonitor("HDMI-A-4");   // went down, answered again: restarted
        assertEquals(1, restarts.get());
    }

    @Test
    void selectMonitorFailsIfSunshineNeverRestarts() {
        restartWorks = false;
        var e = assertThrows(SunshineClient.SunshineException.class, () -> client("pass").selectMonitor("HDMI-A-4"));
        assertTrue(e.getMessage().contains("did not come back"), e.getMessage());
    }

    @Test
    void readsMaxBitrate() throws Exception {
        assertEquals(20_000, client("pass").maxBitrateKbps());
        config = "{\"capture\":\"kms\",\"status\":true}";
        assertEquals(0, client("pass").maxBitrateKbps(), "no cap configured");
    }

    @Test
    void readsHowManyStreamsSunshineAllows() throws Exception {
        config = "{\"capture\":\"kms\",\"status\":true}";
        assertEquals(1, client("pass").maxStreams(), "Sunshine's default");
        config = "{\"channels\":\"3\",\"status\":true}";
        assertEquals(3, client("pass").maxStreams(), "the web UI stores numbers as text");
        config = "{\"channels\":0,\"status\":true}";
        assertEquals(1, client("pass").maxStreams());
    }

    @Test
    void ensureRunningChecksTheWebUiPort() throws Exception {
        try (java.net.ServerSocket sunshine = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            SunshineClient.ensureRunning(java.net.URI.create("https://127.0.0.1:" + sunshine.getLocalPort()));
        }
        int closed;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closed = s.getLocalPort();
        }
        java.io.IOException e = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> SunshineClient.ensureRunning(java.net.URI.create("https://127.0.0.1:" + closed)));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().startsWith("Sunshine isn't running"), e.getMessage());
    }

    @Test
    void saysSunshineIsntRunningInsteadOfConnectException() throws Exception {
        int closed;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }
        SunshineClient stopped = new SunshineClient(java.net.URI.create("http://127.0.0.1:" + closed), "u", "p");
        java.io.IOException e = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, stopped::listMonitors);
        org.junit.jupiter.api.Assertions.assertEquals("Sunshine isn't running (nothing answers at http://127.0.0.1:"
                + closed + ")", AgentRegistry.rootMessage(e));
    }
}
