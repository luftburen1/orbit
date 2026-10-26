package orbit.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import orbit.protocol.MessageCodec;

/**
 * Client for the Sunshine web API (the endpoints its web UI uses), authenticated with the web UI credentials.
 *
 * <p>Sunshine serves a self-signed certificate. It is accepted without verification only when Sunshine runs on this
 * machine (a loopback URL); remote Sunshine URLs need a certificate the JVM trusts.
 */
public final class SunshineClient {

    /**
     * A Sunshine application, as listed under Applications in its web UI.
     *
     * @param launches  it runs a command or a detached command when a stream starts; Desktop doesn't
     * @param imagePath its cover as Sunshine has it: an absolute path, a name in Sunshine's own folder (e.g.
     *                  {@code steam.png}), or empty
     */
    public record App(String name, boolean launches, String imagePath) {

        /** Where Sunshine keeps the covers it comes with, as its packages install it on this OS. */
        static final List<Path> ASSETS = assetFolders(System.getProperty("os.name", ""), System.getenv());

        static List<Path> assetFolders(String osName, Map<String, String> env) {
            String os = osName.toLowerCase(Locale.ROOT);
            if (os.startsWith("windows")) {
                return Stream.of(env.get("ProgramFiles"), env.get("ProgramFiles(x86)"), "C:\\Program Files")
                        .filter(Objects::nonNull).distinct().map(dir -> Path.of(dir, "Sunshine", "assets")).toList();
            }
            if (os.startsWith("mac")) {
                return List.of(Path.of("/opt/homebrew/share/sunshine"), Path.of("/usr/local/share/sunshine"));
            }
            return List.of(Path.of("/usr/share/sunshine"), Path.of("/usr/local/share/sunshine"));
        }

        /** The cover's file on this machine, which is Sunshine's: empty when there's none or it's missing. */
        public Optional<Path> cover() {
            return cover(ASSETS);
        }

        Optional<Path> cover(List<Path> assets) {
            if (imagePath == null || imagePath.isBlank()) {
                return Optional.empty();
            }
            Path path = Path.of(imagePath);
            List<Path> candidates = path.isAbsolute()
                    ? List.of(path) : assets.stream().map(a -> a.resolve(path)).toList();
            return candidates.stream().filter(Files::isRegularFile).findFirst();
        }
    }

    /**
     * A Moonlight client paired with Sunshine.
     *
     * @param enabled false when it's disabled in Sunshine's web UI, which refuses its streams; true if Sunshine doesn't
     *                say (older versions have no such setting)
     */
    public record PairedClient(String name, String uuid, boolean enabled) {
        public PairedClient(String name, String uuid) {
            this(name, uuid, true);
        }
    }

    /**
     * A display Sunshine can capture, from the monitor list it logs at startup.
     *
     * @param id          what {@code output_name} accepts, e.g. the KMS connector name {@code DP-2}
     * @param index       Sunshine's monitor index, which {@code output_name} also accepts
     * @param description the monitor's make and model
     */
    public record Monitor(String id, int index, String description) {
    }

    /** Sunshine answered, but not with success. */
    public static final class SunshineException extends IOException {
        public SunshineException(String message) {
            super(message);
        }

        public SunshineException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final int PIN_ATTEMPTS = 10;
    static final Duration PIN_RETRY_DELAY = Duration.ofMillis(1500);
    static final Duration RESTART_POLL = Duration.ofSeconds(1);
    static final Duration RESTART_TIMEOUT = Duration.ofSeconds(30);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** Keys {@code GET /api/config} adds that are not settings; they must not be written back. */
    private static final List<String> NON_CONFIG_KEYS = List.of("status", "platform", "version");
    private static final String MONITOR_LIST_START = "Start of KMS monitor list";
    private static final String MONITOR_LIST_END = "End of KMS monitor list";
    private static final Pattern MONITOR_LINE = Pattern.compile("Monitor (\\d+) is ([^:\\s]+): (.*)$");
    private static final String ENCODER_TEST_START = "Testing for available encoders";
    private static final Pattern FOUND_ENCODER = Pattern.compile("Found (H\\.264|HEVC|AV1) encoder:");

    private static final ObjectMapper MAPPER = MessageCodec.newMapper();

    private final URI base;
    private final String authorization;
    private final HttpClient http;
    private final Duration pinRetryDelay;
    private final Duration restartPoll;
    private final Duration restartTimeout;

    /**
     * Checks that something answers on Sunshine's web UI port. The web UI runs inside Sunshine, so this tells whether
     * Sunshine runs at all; it needs no login.
     *
     * @throws IOException saying Sunshine isn't running, if nothing answers
     */
    public static void ensureRunning(URI base) throws IOException {
        int port = base.getPort() != -1 ? base.getPort() : "http".equals(base.getScheme()) ? 80 : 443;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(base.getHost(), port), 2000);
        } catch (IOException e) {
            throw new IOException("Sunshine isn't running: nothing answers at " + base
                    + ". Start Sunshine and try again.", e);
        }
    }

    public SunshineClient(URI base, String user, String password) {
        this(base, user, password, PIN_RETRY_DELAY, RESTART_POLL, RESTART_TIMEOUT);
    }

    SunshineClient(URI base, String user, String password, Duration pinRetryDelay, Duration restartPoll,
                   Duration restartTimeout) {
        this.base = base;
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.pinRetryDelay = pinRetryDelay;
        this.restartPoll = restartPoll;
        this.restartTimeout = restartTimeout;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        if ("https".equalsIgnoreCase(base.getScheme()) && isLoopback(base.getHost())) {
            builder.sslContext(trustAnyCertificate());
        }
        this.http = builder.build();
    }

    /**
     * Submits the PIN shown by a pairing Moonlight client. Sunshine only accepts it once Moonlight's pairing request
     * has arrived, so a refusal is retried for a few seconds.
     */
    public CompletableFuture<Void> submitPin(String pin, String clientName) {
        return submitPin(pin, clientName, null);
    }

    /**
     * Enters a client's pairing PIN, retrying while Sunshine hasn't received the client's pairing request yet.
     *
     * @param address the client's IP address, to pick its request when several are waiting; may be null
     */
    public CompletableFuture<Void> submitPin(String pin, String clientName, String address) {
        return submitPin(pin, clientName, address, 1);
    }

    /**
     * A pairing request waiting in Sunshine for its PIN ({@code GET /api/pin}, Sunshine 2026 and later).
     *
     * @param name what Moonlight calls itself, may be empty
     */
    public record PendingPairing(String id, String name, String address) {
    }

    /**
     * Pairs a waiting request with the PIN its Moonlight shows, naming it {@code name} in Sunshine. One attempt: the
     * request is already there, so a refusal means a wrong PIN or a request that's gone.
     */
    public void pair(PendingPairing request, String pin, String name) throws IOException, InterruptedException {
        if (!succeeded(post("/api/pin", Map.of("pin", pin, "name", name, "pairing_id", request.id())))) {
            throw new SunshineException("Sunshine didn't accept the PIN; check it, or pair again in Moonlight");
        }
    }

    private CompletableFuture<Void> submitPin(String pin, String clientName, String address, int attempt) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return tryPin(pin, clientName, address);
            } catch (IOException e) {
                throw new CompletionException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        }, Background.EXECUTOR).thenCompose(accepted -> {
            if (accepted) {
                return CompletableFuture.<Void>completedFuture(null);
            }
            if (attempt >= PIN_ATTEMPTS) {
                return CompletableFuture.<Void>failedFuture(new SunshineException(
                        "Sunshine did not accept the PIN; is the client's pairing request reaching Sunshine?"));
            }
            return CompletableFuture.supplyAsync(() -> null,
                            CompletableFuture.delayedExecutor(pinRetryDelay.toMillis(), TimeUnit.MILLISECONDS))
                    .thenCompose(ignored -> submitPin(pin, clientName, address, attempt + 1));
        });
    }

    /**
     * One attempt. Newer Sunshine wants the id of the waiting pairing request with the PIN; older Sunshine has no
     * list of waiting requests and takes the PIN alone.
     *
     * @return false if Sunshine has no matching request yet (Moonlight may not have reached it), so try again
     */
    private boolean tryPin(String pin, String clientName, String address) throws IOException, InterruptedException {
        Optional<List<PendingPairing>> pending = pendingPairings();
        if (pending.isEmpty()) {
            return succeeded(post("/api/pin", Map.of("pin", pin, "name", clientName)));
        }
        Optional<PendingPairing> request = choose(pending.get(), address);
        if (request.isEmpty()) {
            if (pending.get().size() > 1) {
                throw new SunshineException("several clients are waiting to pair in Sunshine, and none from "
                        + address + "; enter the PIN in Sunshine's web UI");
            }
            return false;
        }
        return succeeded(post("/api/pin", Map.of("pin", pin, "name", clientName, "pairing_id", request.get().id())));
    }

    /** The client's request: the one from its address, else the only one waiting. */
    static Optional<PendingPairing> choose(List<PendingPairing> pending, String address) {
        if (address != null) {
            Optional<PendingPairing> fromAddress = pending.stream()
                    .filter(p -> plainAddress(p.address()).equals(plainAddress(address))).findFirst();
            if (fromAddress.isPresent()) {
                return fromAddress;
            }
        }
        return pending.size() == 1 ? Optional.of(pending.getFirst()) : Optional.empty();
    }

    /** "::ffff:192.168.1.50" and "192.168.1.50" are the same client. */
    static String plainAddress(String address) {
        if (address == null) {
            return "";
        }
        String a = address.strip();
        return a.regionMatches(true, 0, "::ffff:", 0, 7) ? a.substring(7) : a;
    }

    /**
     * Sunshine's waiting pairing requests, or empty if this Sunshine doesn't list them (older versions answer the
     * path with their web page).
     */
    Optional<List<PendingPairing>> pendingPairings() throws IOException, InterruptedException {
        HttpRequest request = request("/api/pin").GET().build();
        HttpResponse<String> response = exchange(request);
        if (response.statusCode() == 404 || response.statusCode() == 405) {
            return Optional.empty();
        }
        check(request, response);
        String body = response.body();
        JsonNode json;
        try {
            json = MAPPER.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Optional.empty();
        }
        if (json == null || !json.path("pairings").isArray()) {
            return Optional.empty();
        }
        List<PendingPairing> pending = new ArrayList<>();
        json.path("pairings").forEach(p -> pending.add(new PendingPairing(p.path("id").asText(),
                p.path("name").asText(), p.path("address").asText())));
        return Optional.of(pending);
    }

    public List<App> listApps() throws IOException, InterruptedException {
        JsonNode response = get("/api/apps");
        List<App> apps = new ArrayList<>();
        response.path("apps").forEach(app -> {
            JsonNode detached = app.path("detached");
            boolean launches = !app.path("cmd").asText().isBlank() || detached.isArray() && !detached.isEmpty();
            apps.add(new App(app.path("name").asText(), launches, app.path("image-path").asText()));
        });
        return apps;
    }

    public List<PairedClient> listClients() throws IOException, InterruptedException {
        JsonNode response = get("/api/clients/list");
        List<PairedClient> clients = new ArrayList<>();
        response.path("named_certs").forEach(c ->
                clients.add(new PairedClient(c.path("name").asText(), c.path("uuid").asText(),
                        c.path("enabled").asBoolean(true))));
        return clients;
    }

    public void unpair(String uuid) throws IOException, InterruptedException {
        if (!succeeded(post("/api/clients/unpair", Map.of("uuid", uuid)))) {
            throw new SunshineException("Sunshine refused to unpair " + uuid);
        }
    }

    // ---- monitors ----

    /**
     * Displays Sunshine can capture. Sunshine has no API for this; its web UI points users to the monitor list in
     * the log, so this reads the latest such list from {@code /api/logs}. Empty if the log has none, which is the
     * case for capture methods other than KMS.
     */
    public List<Monitor> listMonitors() throws IOException, InterruptedException {
        return parseMonitors(getText("/api/logs"));
    }

    /**
     * The {@link orbit.protocol.StreamSettings#VIDEO_CODECS} Sunshine can stream: the encoders it found when it last
     * tested them (logged at startup, as Sunshine has no API for it), less those its {@code hevc_mode} or
     * {@code av1_mode} turn off. Empty if the log doesn't say.
     */
    public Optional<List<String>> videoEncoders() throws IOException, InterruptedException {
        Optional<List<String>> found = parseEncoders(getText("/api/logs"));
        if (found.isEmpty()) {
            return found;
        }
        JsonNode config = get("/api/config");
        // Mode 1 is "not advertised to clients"; 0 (the default) follows the encoder, 2 and 3 pick profiles.
        return Optional.of(found.get().stream()
                .filter(c -> !c.equals("HEVC") || !config.path("hevc_mode").asText("").equals("1"))
                .filter(c -> !c.equals("AV1") || !config.path("av1_mode").asText("").equals("1"))
                .toList());
    }

    /** Sunshine's {@code max_bitrate} in kbps, which caps every stream; 0 when there is no cap. */
    public int maxBitrateKbps() throws IOException, InterruptedException {
        return get("/api/config").path("max_bitrate").asInt(0);
    }

    /** How many clients Sunshine streams to at once: its {@code channels} setting, 1 if unset. */
    public int maxStreams() throws IOException, InterruptedException {
        return Math.max(1, get("/api/config").path("channels").asInt(1));
    }

    /**
     * The id of the app Sunshine runs, 0 if none. From Moonlight's {@code serverinfo} on Sunshine's HTTP port, which
     * is the web UI's less one and needs no login. The id is Sunshine's own, which the web API doesn't show.
     */
    public long currentGame() throws IOException, InterruptedException {
        URI serverinfo = URI.create("http://" + base.getHost() + ":" + (base.getPort() - 1) + "/serverinfo");
        HttpRequest request = HttpRequest.newBuilder(serverinfo).timeout(REQUEST_TIMEOUT).GET().build();
        return currentGame(text(request));
    }

    /** The {@code currentgame} of a {@code serverinfo} answer, 0 if none. */
    static long currentGame(String serverinfo) {
        Matcher m = CURRENT_GAME.matcher(serverinfo);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    private static final Pattern CURRENT_GAME = Pattern.compile("<currentgame>(\\d+)</currentgame>");

    /** The configured {@code output_name}; empty when Sunshine picks the display itself. */
    public String currentMonitor() throws IOException, InterruptedException {
        return get("/api/config").path("output_name").asText("");
    }

    /**
     * Makes Sunshine stream {@code id} (empty for Sunshine's default) and restarts Sunshine to apply it, as its web
     * UI's Apply button does. Restarting ends any running stream. Returns once the restarted Sunshine answers.
     */
    public void selectMonitor(String id) throws IOException, InterruptedException {
        if (!(get("/api/config") instanceof ObjectNode config)) {
            throw new SunshineException("Sunshine returned an unexpected configuration");
        }
        NON_CONFIG_KEYS.forEach(config::remove);
        if (id == null || id.isBlank()) {
            config.remove("output_name");
        } else {
            config.put("output_name", id);
        }
        String startedBefore = firstLine(getText("/api/logs"));
        if (refused(post("/api/config", config))) {
            throw new SunshineException("Sunshine did not save the configuration");
        }
        try {
            text(request("/api/restart").POST(HttpRequest.BodyPublishers.noBody()).build());
        } catch (IOException e) {
            // Expected: Sunshine may exit before it answers.
        }
        awaitRestart(startedBefore);
    }

    /**
     * Waits until Sunshine answers with a new log. Sunshine starts a fresh log on every start, so a different first
     * line (the startup timestamp) shows that the restart has happened and the new process is ready.
     */
    private void awaitRestart(String startedBefore) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + restartTimeout.toNanos();
        boolean sawDown = false;
        while (System.nanoTime() < deadline) {
            Thread.sleep(restartPoll.toMillis());
            try {
                String first = firstLine(getText("/api/logs"));
                // A new log is the clearest sign; answering again after being down also counts, in case a Sunshine
                // version starts its log differently.
                if (!first.equals(startedBefore) || sawDown) {
                    return;
                }
            } catch (IOException e) {
                sawDown = true;
            }
        }
        throw new SunshineException("Sunshine did not come back after restart");
    }

    static List<Monitor> parseMonitors(String log) {
        List<Monitor> monitors = new ArrayList<>();
        List<Monitor> block = null;
        for (String line : log.lines().toList()) {
            if (line.contains(MONITOR_LIST_START)) {
                block = new ArrayList<>();
            } else if (line.contains(MONITOR_LIST_END)) {
                if (block != null) {
                    monitors = block;
                }
                block = null;
            } else if (block != null) {
                Matcher m = MONITOR_LINE.matcher(line);
                if (m.find()) {
                    block.add(new Monitor(m.group(2), Integer.parseInt(m.group(1)), m.group(3).strip()));
                }
            }
        }
        return monitors;
    }

    /** The codecs of the last encoder test in Sunshine's log, in the order Sunshine logs them. */
    static Optional<List<String>> parseEncoders(String log) {
        List<String> found = null;
        for (String line : log.lines().toList()) {
            if (line.contains(ENCODER_TEST_START)) {
                found = new ArrayList<>();
            } else if (found != null) {
                Matcher m = FOUND_ENCODER.matcher(line);
                if (m.find()) {
                    found.add(m.group(1));
                }
            }
        }
        return Optional.ofNullable(found).filter(f -> !f.isEmpty()).map(List::copyOf);
    }

    private static String firstLine(String text) {
        return text.lines().findFirst().orElse("");
    }

    // ---- HTTP ----

    private JsonNode get(String path) throws IOException, InterruptedException {
        return json(text(request(path).header("Accept", "application/json").GET().build()));
    }

    private String getText(String path) throws IOException, InterruptedException {
        return text(request(path).GET().build());
    }

    private JsonNode post(String path, Object body) throws IOException, InterruptedException {
        HttpRequest.Builder request = request(path)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        csrfToken().ifPresent(token -> request.header("X-CSRF-Token", token));
        return json(text(request.build()));
    }

    /**
     * Sunshine's CSRF token for changes, where it has one ({@code GET /api/csrf-token}). Current Sunshine skips the
     * check for requests without an Origin or Referer, as Orbit's are, so this is just in case a later one doesn't.
     */
    private Optional<String> csrfToken() throws InterruptedException {
        try {
            String token = get("/api/csrf-token").path("csrf_token").asText("");
            return token.isEmpty() ? Optional.empty() : Optional.of(token);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(base.resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", authorization);
    }

    /** The response's body, once it says the request succeeded. */
    private String text(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = exchange(request);
        check(request, response);
        return response.body();
    }

    private static JsonNode json(String body) throws IOException {
        return body.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
    }

    /** Sends a request, saying in plain words when Sunshine isn't there instead of Java's bare exception names. */
    private HttpResponse<String> exchange(HttpRequest request) throws IOException, InterruptedException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            throw new SunshineException("Sunshine isn't running (nothing answers at " + base + ")", e);
        } catch (HttpTimeoutException e) {
            throw new SunshineException("Sunshine didn't answer in time (" + base + ")", e);
        }
    }

    private static void check(HttpRequest request, HttpResponse<String> response) throws SunshineException {
        if (response.statusCode() == 401) {
            throw new SunshineException("Sunshine rejected the username or password");
        }
        if (response.statusCode() / 100 != 2) {
            throw new SunshineException("Sunshine answered HTTP " + response.statusCode()
                    + " for " + request.uri().getPath() + errorText(response.body()));
        }
    }

    /** Sunshine's own reason from an error response ({@code {"error": "..."}}), as ": reason", else nothing. */
    private static String errorText(String body) {
        try {
            String error = body == null || body.isBlank() ? "" : MAPPER.readTree(body).path("error").asText("");
            return error.isBlank() ? "" : ": " + error;
        } catch (IOException e) {
            return "";
        }
    }

    /** Sunshine reports {@code "status": true} or {@code "status": "true"} depending on version. */
    private static boolean succeeded(JsonNode response) {
        JsonNode status = response.path("status");
        return status.asBoolean(false) || "true".equalsIgnoreCase(status.asText());
    }

    /** True if the response reports failure; a response without a status counts as success. */
    private static boolean refused(JsonNode response) {
        return response.has("status") && !succeeded(response);
    }

    private static boolean isLoopback(String host) {
        try {
            return host != null && InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static SSLContext trustAnyCertificate() {
        TrustManager trustAll = new X509ExtendedTrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {trustAll}, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
