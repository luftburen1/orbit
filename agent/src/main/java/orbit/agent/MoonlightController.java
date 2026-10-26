package orbit.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import orbit.protocol.StreamSettings;

/** Runs the moonlight-qt command line and tracks the stream it started. */
public class MoonlightController {

    private static final Logger LOG = Logger.getLogger(MoonlightController.class.getName());

    /**
     * How long a new stream gets to find the host and the app. moonlight-qt looks for the host for 30 s and for the
     * app for 10 s more, and then shows its error on screen and stays open without logging it.
     */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(45);
    /**
     * How long a stream gets to connect once moonlight-qt's streaming session exists, i.e. it found the host and the
     * app: it waits up to 120 s for the host to launch the app (prep commands run first), then connects.
     */
    static final Duration SESSION_TIMEOUT = Duration.ofSeconds(135);
    static final Duration QUICK_COMMAND_TIMEOUT = Duration.ofSeconds(20);
    static final Duration PAIR_TIMEOUT = Duration.ofSeconds(110);
    /** A pairing check this slow is logged with its time; {@code list} normally takes a third of a second. */
    static final Duration SLOW_LIST = Duration.ofSeconds(5);
    /**
     * moonlight-qt logs this whenever a stream's connection ends. After a normal end it exits by itself; after an
     * error (e.g. Sunshine restarted) it shows the error and stays open, which would look like a running stream.
     */
    private static final Pattern CONNECTION_TERMINATED = Pattern.compile("Connection terminated: (-?\\d+)");
    /**
     * moonlight-common-c's connection stages, which moonlight-qt logs at Info level: "Starting RTSP handshake...",
     * then "done" or "failed: -1" on a line of its own. The input stream is the last stage; once it's done, the stream
     * runs. A failed stage leaves moonlight-qt open on an error dialog.
     */
    private static final Pattern STAGE = Pattern.compile(
            "SDL Info \\(\\d+\\): ((?:Resolving|Initializing|Starting) [^.]*)\\.\\.\\.\\s*$");
    private static final Pattern STAGE_DONE = Pattern.compile("SDL Info \\(\\d+\\): done\\s*$");
    private static final Pattern STAGE_FAILED = Pattern.compile(
            "SDL Info \\(\\d+\\): (?:\\w+ stream start )?failed: (-?\\d+)");
    private static final String LAST_STAGE = "Starting input stream";
    /** Only moonlight-qt's streaming session logs through SDL; looking for the host and the app logs through Qt. */
    private static final Pattern SESSION_LINE = Pattern.compile(" - SDL \\w+ \\(\\d+\\): ");
    /** moonlight-qt's warnings and errors, e.g. a refused "serverinfo" request: the likely reason it didn't connect. */
    private static final Pattern PROBLEM = Pattern.compile(
            " - (?:Qt Warning|Qt Critical|SDL Warn|SDL Error|SDL Critical)(?: \\(\\d+\\))?: (.+)$");
    /** How long Moonlight may stay open after its connection ended before the agent closes it. */
    static final Duration TERMINATION_GRACE = Duration.ofSeconds(3);

    /** Starts processes; replaced in tests. */
    public interface Launcher {
        Process start(List<String> command) throws IOException;
    }

    /** Result of a short-lived moonlight command. */
    public record Outcome(boolean ok, String output) {
    }

    /** Changes when Moonlight is installed while the agent runs; nothing runs from the old command then. */
    private volatile List<String> moonlight;
    private final Launcher launcher;
    private final Runnable onStreamEnded;
    Duration connectTimeout = CONNECT_TIMEOUT;
    Duration sessionTimeout = SESSION_TIMEOUT;
    Duration pairTimeout = PAIR_TIMEOUT;
    /** How long Moonlight gets after a successful pairing before its window is closed. */
    Duration pairSaveGrace = Duration.ofSeconds(2);
    Duration terminationGrace = TERMINATION_GRACE;
    private Process stream;
    private String streamApp;
    /**
     * How long a look for streams started outside Orbit is reused: it reads every process in {@code /proc}, and
     * {@link #isStreaming} is asked on every remote key press and idle screen update.
     */
    Duration externalCheckAge = Duration.ofSeconds(1);
    /** The last look for streams started outside Orbit, or null when it's stale. */
    private volatile ExternalCheck externalCheck;

    /** Whether a stream started outside Orbit ran at {@code at} ({@link System#nanoTime}). */
    private record ExternalCheck(long at, boolean running) {
    }

    public MoonlightController(List<String> moonlight, Launcher launcher, Runnable onStreamEnded) {
        this.moonlight = List.copyOf(moonlight);
        this.launcher = launcher;
        this.onStreamEnded = onStreamEnded;
    }

    /** Switches to another Moonlight command, e.g. once Moonlight has been installed. */
    public void setCommand(List<String> command) {
        this.moonlight = List.copyOf(command);
    }

    public List<String> command() {
        return moonlight;
    }

    public static Launcher systemLauncher() {
        return command -> new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    /**
     * For a text console: moonlight-qt's own screens (its "Starting…" and connection steps, errors, "Quitting") go
     * nowhere, and the agent's idle screen stays on the display until the stream's picture replaces it. Without X11
     * or Wayland, moonlight-qt draws its Qt UI on the display with Qt's eglfs; Qt's offscreen platform draws it in
     * memory instead, while the stream still takes the display through SDL's KMS/DRM. A {@code QT_QPA_PLATFORM} set
     * by the user is left alone.
     */
    public static Launcher consoleLauncher() {
        return command -> {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().putIfAbsent("QT_QPA_PLATFORM", "offscreen");
            return builder.start();
        };
    }

    // ---- argument building ----

    List<String> streamArgs(String host, String app, StreamSettings s) {
        List<String> args = command("stream", host, app,
                "--resolution", s.resolution(),
                "--fps", Integer.toString(s.fps()),
                "--bitrate", Integer.toString(s.bitrateKbps()));
        if (s.videoCodec() != null) {
            args.addAll(List.of("--video-codec", s.videoCodec()));
        }
        toggle(args, "vsync", s.vsync());
        toggle(args, "frame-pacing", s.framePacing());
        if (s.audioConfig() != null) {
            args.addAll(List.of("--audio-config", s.audioConfig()));
        }
        toggle(args, "performance-overlay", s.performanceOverlay());
        args.addAll(s.extraFlags());
        return args;
    }

    /** Moonlight's {@code --name} or {@code --no-name}; nothing for its own default. */
    private static void toggle(List<String> args, String name, Boolean on) {
        if (on != null) {
            args.add(on ? "--" + name : "--no-" + name);
        }
    }

    List<String> pairArgs(String host, String pin) {
        return command("pair", host, "--pin", pin);
    }

    List<String> quitArgs(String host) {
        return command("quit", host);
    }

    List<String> listArgs(String host) {
        return command("list", host);
    }

    private List<String> command(String... args) {
        List<String> full = new ArrayList<>(moonlight);
        full.addAll(List.of(args));
        return full;
    }

    // ---- streaming ----

    /**
     * Starts streaming {@code app} and waits until Moonlight's connection is up. Moonlight shows most failures (host
     * not found, a connection stage failing) on screen and stays open, so it's closed then.
     *
     * @throws IOException with the reason, e.g. Moonlight's output if it exits, when the stream didn't start
     */
    public void startStream(String host, String app, StreamSettings settings) throws IOException, InterruptedException {
        startStream(host, app, settings, false);
    }

    /**
     * @param quitAppOnFailure quit the app on the host if the stream doesn't start once Moonlight's session has begun:
     *                         from then on Moonlight may have launched it, and nobody else would quit it
     */
    public void startStream(String host, String app, StreamSettings settings, boolean quitAppOnFailure)
            throws IOException, InterruptedException {
        Process process;
        OutputTail output;
        ConnectWatch watch = new ConnectWatch();
        synchronized (this) {
            if (isStreaming()) {
                throw new IllegalStateException("already streaming " + (streamApp != null ? streamApp : ""));
            }
            process = launcher.start(streamArgs(host, app, settings));
            Process started = process;
            output = OutputTail.drain(process, "moonlight-stream", line -> {
                watch.line(line);
                watchForTermination(started, line);
            });
            stream = process;
            streamApp = app;
            externalCheck = null;
        }
        process.onExit().thenRun(() -> {
            LOG.info(() -> "stream of " + app + " ended with exit code " + process.exitValue());
            synchronized (this) {
                if (stream == process) {
                    stream = null;
                    streamApp = null;
                }
                externalCheck = null;
            }
            onStreamEnded.run();
        });
        try {
            awaitConnection(host, process, watch, output);
        } catch (IOException e) {
            LOG.info(() -> "stream of " + app + " didn't start: " + e.getMessage());
            if (process.isAlive()) {
                terminate(process.toHandle());
            }
            if (quitAppOnFailure && watch.sessionStart != 0) {
                throw new IOException(e.getMessage() + "; " + quitAfterFailedStart(host, app), e);
            }
            throw e;
        }
        LOG.info(() -> "streaming " + app + " from " + host);
    }

    /** Quits the app Moonlight may have launched for a stream that didn't start; says how that went. */
    private String quitAfterFailedStart(String host, String app) throws InterruptedException {
        LOG.info(() -> "quitting " + app + " on the host, since its stream didn't start");
        try {
            Outcome quit = run(quitArgs(host), QUICK_COMMAND_TIMEOUT);
            if (quit.ok()) {
                return "quit " + app + " on the host";
            }
            LOG.warning(() -> "moonlight quit failed: " + quit.output());
            return "quitting " + app + " on the host failed: " + quit.output();
        } catch (IOException e) {
            LOG.warning(() -> "cannot run moonlight quit: " + e.getMessage());
            return "quitting " + app + " on the host failed: " + e.getMessage();
        }
    }

    private void awaitConnection(String host, Process process, ConnectWatch watch, OutputTail output)
            throws IOException, InterruptedException {
        CompletableFuture<Object> done = CompletableFuture.anyOf(watch.connected, process.onExit());
        long start = System.nanoTime();
        while (!done.isDone()) {
            // The deadline moves once the session starts: from then on the host may take long to launch the app.
            long session = watch.sessionStart;
            long deadline = session != 0 ? session + sessionTimeout.toNanos() : start + connectTimeout.toNanos();
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                String problem = watch.problem;
                throw new IOException("Moonlight didn't connect to " + host + " within "
                        + Duration.ofNanos(System.nanoTime() - start).toSeconds() + " s"
                        + (session == 0 ? " (it didn't get as far as starting a session)" : "")
                        + (problem != null ? "; last problem: " + problem : ""));
            }
            try {
                done.get(Math.min(left, TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                // look at the deadline again
            } catch (ExecutionException e) {
                throw new IOException(e.getCause().getMessage(), e.getCause());
            }
        }
        if (!watch.connected.isDone()) {
            output.awaitEnd();
            throw new IOException("Moonlight exited with code " + process.exitValue() + ": " + output.text());
        }
        watch.connected.join(); // rethrows a failure that came in together with the exit
    }

    /** Follows Moonlight's output, on its reader thread, until the connection is up or has failed. */
    private static final class ConnectWatch {
        final CompletableFuture<Void> connected = new CompletableFuture<>();
        /** {@link System#nanoTime} of the session's first line, or 0 before it. */
        volatile long sessionStart;
        private String stage;
        /**
         * Set once the last stage starts: its "done" can come after other lines (decoder, audio), even ones that look
         * like a stage.
         */
        private boolean lastStageStarted;
        volatile String problem;

        void line(String line) {
            if (connected.isDone()) {
                return;
            }
            if (sessionStart == 0 && SESSION_LINE.matcher(line).find()) {
                sessionStart = System.nanoTime();
            }
            Matcher m;
            if ((m = STAGE.matcher(line)).find()) {
                stage = m.group(1);
                lastStageStarted |= LAST_STAGE.equals(stage);
            } else if (STAGE_DONE.matcher(line).find()) {
                if (lastStageStarted) {
                    connected.complete(null);
                }
                stage = null;
            } else if ((m = STAGE_FAILED.matcher(line)).find()) {
                fail((stage != null ? stage : "a connection stage") + " failed (error " + m.group(1) + ")");
            } else if ((m = CONNECTION_TERMINATED.matcher(line)).find()) {
                fail("the connection ended while starting (code " + m.group(1) + ")");
            } else if ((m = PROBLEM.matcher(line)).find()) {
                problem = m.group(1).strip();
            }
        }

        private void fail(String reason) {
            String last = problem;
            connected.completeExceptionally(new IOException("Moonlight: " + reason
                    + (last != null ? "; last problem: " + last : "")));
        }
    }

    /** Closes Moonlight shortly after it reports that its connection ended, unless it exits by itself. */
    private void watchForTermination(Process process, String line) {
        Matcher m = CONNECTION_TERMINATED.matcher(line);
        if (!m.find()) {
            return;
        }
        String code = m.group(1);
        LOG.info(() -> "moonlight reports the stream connection ended (code " + code + ")");
        CompletableFuture.delayedExecutor(terminationGrace.toMillis(), TimeUnit.MILLISECONDS).execute(() -> {
            if (process.isAlive()) {
                LOG.info("moonlight stayed open after its connection ended; closing it");
                try {
                    terminate(process.toHandle());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    /** Asks the host to quit the running app, then makes sure the local stream process is gone. */
    public void stopStream(String host) throws IOException, InterruptedException {
        Outcome quit = run(quitArgs(host), QUICK_COMMAND_TIMEOUT);
        Process process;
        synchronized (this) {
            process = stream;
        }
        if (process != null && !process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
        if (!quit.ok() && process == null) {
            throw new IOException("moonlight quit failed: " + quit.output());
        }
    }

    /**
     * Closes the local Moonlight stream without asking the host to quit the app. Also closes streams started outside
     * Orbit, since those would break too when Sunshine restarts.
     */
    public void disconnect() throws InterruptedException {
        stopOwnStream();
        for (ProcessHandle external : externalStreams().toList()) {
            terminate(external);
        }
        externalCheck = null;
    }

    /**
     * Closes {@code moonlight stream} processes left over from an agent that died, e.g. on a tty1 client, where
     * nothing kills them with it. A stream should end with its agent, since the host can't control it otherwise.
     * Run at startup, before this agent starts any stream of its own. Returns how many it closed.
     */
    public int closeLeftoverStreams() throws InterruptedException {
        List<ProcessHandle> leftovers = externalStreams().toList();
        for (ProcessHandle p : leftovers) {
            LOG.info(() -> "closing a moonlight stream left over from before this agent started (pid " + p.pid() + ")");
            terminate(p);
        }
        externalCheck = null;
        return leftovers.size();
    }

    /**
     * Closes the stream this agent started, if any, without asking the host to quit the app. Streams started
     * outside Orbit are left alone. Returns true if there was one.
     */
    public boolean stopOwnStream() throws InterruptedException {
        Process process;
        synchronized (this) {
            process = stream;
        }
        if (process == null || !process.isAlive()) {
            return false;
        }
        terminate(process.toHandle());
        return true;
    }

    private static void terminate(ProcessHandle process) throws InterruptedException {
        process.destroy();
        try {
            process.onExit().get(5, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            process.destroyForcibly();
        }
    }

    /** This agent's stream runs, or one started outside Orbit (as seen at most {@link #externalCheckAge} ago). */
    public boolean isStreaming() {
        synchronized (this) {
            if (stream != null && stream.isAlive()) {
                return true;
            }
        }
        // Outside the lock: the look through every process takes a while.
        return externalStreamRunning();
    }

    /** App of the stream this agent started, or null (unknown for streams started by hand). */
    public synchronized String currentApp() {
        return stream != null && stream.isAlive() ? streamApp : null;
    }

    /** Detects a {@code moonlight stream} started outside Orbit, e.g. from the Moonlight GUI or a terminal. */
    protected boolean externalStreamRunning() {
        long now = System.nanoTime();
        ExternalCheck last = externalCheck;
        if (last != null && now - last.at() < externalCheckAge.toNanos()) {
            return last.running();
        }
        boolean running = externalStreams().findAny().isPresent();
        externalCheck = new ExternalCheck(now, running);
        return running;
    }

    /** {@code moonlight stream} processes this agent did not start. */
    protected Stream<ProcessHandle> externalStreams() {
        String executable = Path.of(moonlight.getFirst()).getFileName().toString();
        long self;
        synchronized (this) {
            self = stream != null ? stream.pid() : -1;
        }
        return ProcessHandle.allProcesses()
                .filter(p -> p.pid() != self)
                .filter(p -> p.info().command()
                        .map(c -> Path.of(c).getFileName().toString().equalsIgnoreCase(executable))
                        .orElse(false)
                        && p.info().arguments().map(a -> List.of(a).contains("stream")).orElse(false));
    }

    // ---- pairing and status ----

    public enum PairOutcome { PAIRED, ALREADY_PAIRED }

    /**
     * Pairs with {@code host} using {@code pin}. {@code pinReady} is called once {@code moonlight pair} is waiting,
     * so the caller can have the PIN entered in Sunshine.
     *
     * <p>moonlight-qt's {@code pair} shows its result ("Successfully paired", "already paired", an error) on screen
     * and waits for someone to close it, printing nothing. So pairing is skipped when Moonlight can already list the
     * host's apps, and the window is closed once {@code verdict} (Sunshine's, from the host) arrives.
     *
     * @param verdict completes with true once Sunshine has accepted the PIN (pairing done), false if it didn't
     * @throws IOException with Moonlight's output if pairing fails or times out
     */
    public PairOutcome pair(String host, String pin, Runnable pinReady, Future<Boolean> verdict)
            throws IOException, InterruptedException {
        if (isPaired(host)) {
            return PairOutcome.ALREADY_PAIRED;
        }
        Process process = launcher.start(pairArgs(host, pin));
        OutputTail output = OutputTail.drain(process, "moonlight-pair");
        pinReady.run();
        long deadline = System.nanoTime() + pairTimeout.toNanos();
        boolean alreadyPaired = false;
        while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
            if (output.text().toLowerCase(Locale.ROOT).contains("already paired")) {
                alreadyPaired = true;
                terminate(process.toHandle());
                break;
            }
            if (verdict.isDone()) {
                boolean accepted;
                try {
                    accepted = verdict.get();
                } catch (ExecutionException e) {
                    accepted = false;
                }
                if (accepted) {
                    // Give Moonlight a moment to save the pairing before closing its "Successfully paired" window.
                    process.waitFor(pairSaveGrace.toMillis(), TimeUnit.MILLISECONDS);
                    terminate(process.toHandle());
                    break;
                }
                terminate(process.toHandle());
                throw new IOException("Sunshine did not accept the PIN" + tail(output));
            }
            if (System.nanoTime() > deadline) {
                terminate(process.toHandle());
                throw new IOException("pairing timed out after " + pairTimeout.toSeconds() + "s: " + output.text());
            }
        }
        output.awaitEnd();
        if (!isPaired(host)) {
            throw new IOException("pairing failed: " + output.text());
        }
        return alreadyPaired ? PairOutcome.ALREADY_PAIRED : PairOutcome.PAIRED;
    }

    /**
     * What {@code moonlight list} says about pairing with a host.
     *
     * @param detail Moonlight's own words for {@link Kind#UNREACHABLE} and {@link Kind#FAILED}, else null
     */
    public record Pairing(Kind kind, String detail) {
        /**
         * {@code UNREACHABLE}: Moonlight said it failed to connect. {@code NO_ANSWER}: the agent gave up first, which
         * says nothing about the network; {@code list} takes a third of a second normally.
         */
        public enum Kind { PAIRED, NOT_PAIRED, UNREACHABLE, NO_ANSWER, FAILED }
    }

    private static String tail(OutputTail output) {
        String text = output.text().strip();
        return text.isEmpty() ? "" : ": " + text;
    }

    /** True if Moonlight can list this host's apps, which requires pairing. */
    public boolean isPaired(String host) throws IOException, InterruptedException {
        return checkPairing(host).kind() == Pairing.Kind.PAIRED;
    }

    /** Asks Moonlight for the host's apps, and tells "not paired" apart from "can't reach the host". */
    public Pairing checkPairing(String host) throws IOException, InterruptedException {
        long start = System.nanoTime();
        Pairing pairing = classify(run(listArgs(host), QUICK_COMMAND_TIMEOUT));
        long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        if (millis >= SLOW_LIST.toMillis()) {
            LOG.info(() -> "moonlight list took " + millis + " ms (" + pairing.kind() + ")");
        }
        return pairing;
    }

    /**
     * moonlight-qt's {@code list} exits with the same code when the host isn't paired and when it can't be found
     * (after 30 s), so its message tells them apart: "Computer X has not been paired…" or "Failed to connect to X".
     * The agent gives up after {@link #QUICK_COMMAND_TIMEOUT}, before Moonlight's 30 s are up; that's
     * {@code NO_ANSWER}, not unreachable.
     */
    static Pairing classify(Outcome o) {
        if (o.ok()) {
            return new Pairing(Pairing.Kind.PAIRED, null);
        }
        String output = o.output();
        String last = output.lines().map(String::strip).filter(l -> !l.isEmpty()).reduce((a, b) -> b).orElse("");
        if (output.contains("has not been paired")) {
            return new Pairing(Pairing.Kind.NOT_PAIRED, null);
        }
        if (output.startsWith("timed out after")) {
            return new Pairing(Pairing.Kind.NO_ANSWER, "moonlight list " + output);
        }
        if (output.contains("Failed to connect to")) {
            return new Pairing(Pairing.Kind.UNREACHABLE, last.isEmpty() ? "Moonlight can't reach the host" : last);
        }
        return new Pairing(Pairing.Kind.FAILED, last.isEmpty() ? "moonlight list failed" : last);
    }

    /**
     * Runs {@code moonlight --version}. Empty if it ran but said nothing usable.
     *
     * @throws IOException if Moonlight can't be started at all, e.g. it isn't installed
     */
    public Optional<String> version() throws IOException, InterruptedException {
        Outcome o = run(command("--version"), Duration.ofSeconds(10));
        return o.ok() && !o.output().isBlank() ? Optional.of(o.output().lines().toList().getLast().strip())
                : Optional.empty();
    }

    Outcome run(List<String> command, Duration timeout) throws IOException, InterruptedException {
        Process p = launcher.start(command);
        OutputTail output = OutputTail.drain(p, "moonlight-" + command.get(moonlight.size()));
        if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            return new Outcome(false, "timed out after " + timeout.toSeconds() + "s");
        }
        output.awaitEnd();
        return new Outcome(p.exitValue() == 0, output.text());
    }

    /** Reads a process's output on a daemon thread, keeping the last lines for error messages. */
    static final class OutputTail {
        private static final int MAX_LINES = 20;
        private final Deque<String> lines = new ArrayDeque<>();
        private final Thread reader;

        private OutputTail(Process process, String name, Consumer<String> onLine) {
            reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String l = line;
                        LOG.fine(() -> name + ": " + l);
                        onLine.accept(line);
                        synchronized (lines) {
                            if (lines.size() == MAX_LINES) {
                                lines.removeFirst();
                            }
                            lines.addLast(line);
                        }
                    }
                } catch (IOException ignored) {
                    // process went away
                }
            }, name + "-output");
            reader.setDaemon(true);
        }

        static OutputTail drain(Process process, String name) {
            return drain(process, name, line -> { });
        }

        /** @param onLine sees each line on the reader thread */
        static OutputTail drain(Process process, String name, Consumer<String> onLine) {
            OutputTail tail = new OutputTail(process, name, onLine);
            tail.reader.start();
            return tail;
        }

        void awaitEnd() throws InterruptedException {
            reader.join(2000);
        }

        String text() {
            synchronized (lines) {
                return String.join("\n", lines);
            }
        }
    }
}
