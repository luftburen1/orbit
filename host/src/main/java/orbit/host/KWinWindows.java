package orbit.host;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import orbit.protocol.Commands;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.interfaces.DBusInterface;

/**
 * Puts a stream's windows on the monitor Sunshine streams, on KDE Plasma: while a stream runs, a KWin script
 * ({@code place-windows.js}) asks this class about each window that opens or gets focus, and {@link WindowPlacement}
 * decides. A stream of the desktop itself gets {@code focus-desktop.js} instead, which gives focus to the desktop on
 * that monitor and moves the cursor there. {@code focus-stream.js} is a one-shot that gives focus back to the stream
 * for a client's TV remote. While that remote is in key mode, KWin's Hide Cursor effect hides the idle cursor. KWin
 * forgets the scripts and the effect when Plasma restarts, and the scripts ask nobody once Orbit is gone, so nothing is
 * left behind.
 *
 * <p>KWin numbers its scripts by their place in its list and names their D-Bus objects after that number, so a script
 * left loaded makes a later one get a number whose object is the old script's: {@code run()} would then run the old
 * script. Hence one Orbit script at a time: the one-shot goes before any other load, and soon after it ran.
 */
final class KWinWindows implements HostDesktop.Windows, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(KWinWindows.class.getName());
    private static final String KWIN = "org.kde.KWin";
    private static final String STREAM_SCRIPT = "orbit-place-windows";
    private static final String FOCUS_SCRIPT = "orbit-focus-stream";
    /** How long the one-shot focus script gets to run before it's unloaded (KWin runs scripts asynchronously). */
    private static final Duration FOCUS_SCRIPT_RUNS = Duration.ofSeconds(2);
    /** KWin's built-in effect that hides the cursor while it's idle or keys are typed, until the mouse moves. */
    private static final String HIDE_CURSOR = "hidecursor";
    /** Where KWin keeps the effect's settings, in kwinrc. */
    private static final String HIDE_CURSOR_GROUP = "Effect-hidecursor";
    private static final String IDLE_KEY = "InactivityDuration";
    /** Seconds before the cursor hides in key mode, unless the user has set the effect's own. */
    static final int CURSOR_IDLE_SECONDS = 3;
    static final String OBJECT_PATH = "/orbit/Windows";

    @DBusInterfaceName("org.kde.kwin.Scripting")
    public interface Scripting extends DBusInterface {
        int loadScript(String filePath, String pluginName);

        boolean unloadScript(String pluginName);

        boolean isScriptLoaded(String pluginName);
    }

    @DBusInterfaceName("org.kde.kwin.Effects")
    public interface Effects extends DBusInterface {
        boolean isEffectLoaded(String name);

        boolean loadEffect(String name);

        void unloadEffect(String name);

        void reconfigureEffect(String name);
    }

    @DBusInterfaceName("org.kde.kwin.Script")
    public interface Script extends DBusInterface {
        void run();
    }

    /** What {@code place-windows.js} calls for each window. */
    @DBusInterfaceName("orbit.Windows")
    public interface Placer extends DBusInterface {
        /** @return "ignore", "move" or "fullscreen" */
        String place(String window);
    }

    private final DBusConnection bus;
    /** The desktop's own processes, for {@link WindowPlacement}. */
    private final Set<String> shellProcesses;
    private final Scripting scripting;
    private final Effects effects;
    private final String placeTemplate = template("place-windows.js");
    private final String desktopTemplate = template("focus-desktop.js");
    private final String focusTemplate = template("focus-stream.js");
    private final Path streamScriptFile = tempScript(STREAM_SCRIPT);
    private final Path focusScriptFile = tempScript(FOCUS_SCRIPT);
    /** Decides for the current stream; null while nothing streams, or for a desktop stream. Guarded by this. */
    private WindowPlacement placement;
    /** Told once when the current stream's first app window is placed, then null. Guarded by this. */
    private Runnable appFound;
    /** Told once when one of the current stream's app windows is made fullscreen, then null. Guarded by this. */
    private Runnable appFullscreen;
    /** The monitor the current stream shows, while its script is in KWin; else null. Guarded by this. */
    private String output;
    /** Counts focus script loads, so a delayed unload leaves a newer one alone. Guarded by this. */
    private long focusScriptLoads;
    /** Orbit loaded the Hide Cursor effect for this stream (not when the user has it on anyway). Guarded by this. */
    private boolean hidingCursor;
    /** Orbit wrote {@link #IDLE_KEY} into kwinrc for it, and takes it out again. Guarded by this. */
    private boolean wroteIdleTime;

    private KWinWindows(DBusConnection bus, Set<String> shellProcesses) throws DBusException, IOException {
        this.bus = bus;
        this.shellProcesses = shellProcesses;
        this.scripting = bus.getRemoteObject(KWIN, "/Scripting", Scripting.class);
        this.effects = bus.getRemoteObject(KWIN, "/Effects", Effects.class);
        bus.exportObject(OBJECT_PATH, new PlacerObject());
    }

    /**
     * Connects to KWin on the session bus; empty when this isn't a KWin session.
     *
     * @param shellProcesses the desktop's own processes, whose windows never make a stream app's family
     */
    static Optional<KWinWindows> connect(Set<String> shellProcesses) {
        DBusConnection bus = null;
        try {
            bus = SessionBus.whereRunning(KWIN).orElse(null);
            if (bus == null) {
                return Optional.empty();
            }
            KWinWindows windows = new KWinWindows(bus, shellProcesses);
            LOG.info("KWin found: a stream's windows will go to the monitor Sunshine streams");
            return Optional.of(windows);
        } catch (DBusException | DBusExecutionException | IOException | UncheckedIOException e) {
            LOG.log(Level.INFO, "cannot place windows with KWin (" + e.getMessage() + ")", e);
            if (bus != null) {
                SessionBus.close(bus);
            }
            return Optional.empty();
        }
    }

    /** A script from the jar, with the {@code cursor.js} helpers in front. */
    private static String template(String name) {
        return resource("cursor.js") + "\n" + resource(name);
    }

    private static String resource(String name) {
        try (InputStream in = KWinWindows.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException(name + " missing from the jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path tempScript(String name) {
        try {
            Path file = Files.createTempFile(name, ".js");
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The script for this connection and monitor. */
    static String script(String template, String service, String output) {
        return template.replace("@ORBIT_SERVICE@", jsString(service)).replace("@OUTPUT@", jsString(output))
                .replace("@SPLASH@", jsString(LaunchSplash.TITLE)).replace("@NOTICE@", jsString(StartNotice.TITLE));
    }

    private static String jsString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public synchronized boolean placeApp(String output, Runnable appFound, Runnable appFullscreen) {
        if (this.output != null || !startStreamScript(placeTemplate, output, "window placement")) {
            return false;
        }
        placement = new WindowPlacement(WindowPlacement.Processes.PROC, shellProcesses,
                ProcessHandle.current().pid(), System::nanoTime);
        this.appFound = appFound;
        this.appFullscreen = appFullscreen;
        LOG.info(() -> "placing the stream's windows on " + output);
        return true;
    }

    /** So the stream's keys and mouse act on what it shows rather than on the host's own monitor. */
    @Override
    public synchronized void focusDesktop(String output) {
        if (this.output == null && startStreamScript(desktopTemplate, output, "desktop focus")) {
            LOG.info(() -> "focusing the desktop on " + output + " for the stream");
        }
    }

    private boolean startStreamScript(String template, String output, String what) {
        try {
            unloadFocusScript();
            if (scripting.isScriptLoaded(STREAM_SCRIPT)) {
                scripting.unloadScript(STREAM_SCRIPT);
            }
            if (!loadAndRun(streamScriptFile, STREAM_SCRIPT, script(template, bus.getUniqueName(), output), what)) {
                return false;
            }
            this.output = output;
            return true;
        } catch (IOException | DBusException | DBusExecutionException e) {
            LOG.log(Level.WARNING, "cannot load the " + what + " script into KWin", e);
            try {
                // Loaded but not run, it would still take a number in KWin's list.
                scripting.unloadScript(STREAM_SCRIPT);
            } catch (DBusExecutionException ignored) {
                // KWin is unreachable; nothing is loaded then either
            }
            return false;
        }
    }

    @Override
    public synchronized void stop() {
        if (output == null) {
            return;
        }
        hideIdleCursor(false);
        placement = null;
        appFound = null;
        appFullscreen = null;
        output = null;
        try {
            unloadFocusScript();
            scripting.unloadScript(STREAM_SCRIPT);
            LOG.info("stream ended; no longer placing windows");
        } catch (DBusExecutionException e) {
            LOG.log(Level.FINE, "cannot unload the window placement script", e);
        }
    }

    /** Gives focus to the topmost window on the stream's monitor, or its desktop, with {@code focus-stream.js}. */
    @Override
    public synchronized void refocus(boolean pointer) {
        if (output == null) {
            return;
        }
        try {
            unloadFocusScript();
            String text = script(focusTemplate, bus.getUniqueName(), output)
                    .replace("@POINTER@", Boolean.toString(pointer));
            if (!loadAndRun(focusScriptFile, FOCUS_SCRIPT, text, "focus")) {
                return;
            }
            long load = ++focusScriptLoads;
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(FOCUS_SCRIPT_RUNS);
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (this) {
                    if (focusScriptLoads == load) {
                        unloadFocusScript();
                    }
                }
            });
        } catch (IOException | DBusException | DBusExecutionException e) {
            LOG.log(Level.WARNING, "cannot give focus back to the stream", e);
        }
    }

    /**
     * In the remote's key mode the cursor only gets in the way of what the stream shows, so KWin's Hide Cursor effect
     * hides it while it's idle; moving the mouse at the host shows it again.
     */
    @Override
    public synchronized void hideIdleCursor(boolean hide) {
        if (hide == hidingCursor || output == null) {
            return;
        }
        try {
            if (!hide) {
                hidingCursor = false;
                effects.unloadEffect(HIDE_CURSOR);
                forgetIdleTime();
                LOG.info("TV remote in mouse mode: showing the cursor");
            } else if (!effects.isEffectLoaded(HIDE_CURSOR)) {
                // By default the effect never hides an idle cursor, and the remote's keys (arrows, Enter, Escape)
                // don't count as typing to it: it needs an idle time.
                String idle = kwinrc("kreadconfig6", "--key", IDLE_KEY).strip();
                if (idle.isEmpty() || idle.equals("0")) {
                    kwinrc("kwriteconfig6", "--key", IDLE_KEY, Integer.toString(CURSOR_IDLE_SECONDS));
                    wroteIdleTime = true;
                }
                if (effects.loadEffect(HIDE_CURSOR)) {
                    effects.reconfigureEffect(HIDE_CURSOR); // so KWin rereads kwinrc
                    hidingCursor = true;
                    LOG.info("TV remote in key mode: hiding the idle cursor");
                } else {
                    forgetIdleTime();
                }
            }
        } catch (DBusExecutionException | IOException e) {
            LOG.log(Level.INFO, "cannot " + (hide ? "load" : "unload") + " KWin's Hide Cursor effect", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void forgetIdleTime() throws IOException, InterruptedException {
        if (wroteIdleTime) {
            wroteIdleTime = false;
            kwinrc("kwriteconfig6", "--key", IDLE_KEY, "--delete");
        }
    }

    /** Runs {@code kreadconfig6} or {@code kwriteconfig6} on the Hide Cursor group of kwinrc; returns its output. */
    private static String kwinrc(String tool, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(tool, "--file", "kwinrc", "--group", HIDE_CURSOR_GROUP));
        command.addAll(List.of(args));
        Commands.Result result = Commands.run(command, Duration.ofSeconds(5));
        if (!result.ok()) {
            throw new IOException(tool + " failed: " + result.output().strip());
        }
        return result.output();
    }

    /** @return false if KWin refused it */
    private boolean loadAndRun(Path file, String name, String text, String what) throws IOException, DBusException {
        Files.writeString(file, text);
        int id = scripting.loadScript(file.toString(), name);
        if (id < 0) {
            LOG.warning("KWin refused the " + what + " script");
            return false;
        }
        bus.getRemoteObject(KWIN, "/Scripting/Script" + id, Script.class).run();
        return true;
    }

    private void unloadFocusScript() {
        try {
            if (scripting.isScriptLoaded(FOCUS_SCRIPT)) {
                scripting.unloadScript(FOCUS_SCRIPT);
            }
        } catch (DBusExecutionException e) {
            LOG.log(Level.FINE, "cannot unload the focus script", e);
        }
    }

    private synchronized WindowPlacement.Action place(String window) {
        if (placement == null) {
            return WindowPlacement.Action.IGNORE;
        }
        WindowPlacement.Action action = placement.decide(WindowPlacement.Window.parse(window));
        // The first window placed is the app's: nothing else is placed before it.
        if (action != WindowPlacement.Action.IGNORE && appFound != null) {
            appFound.run();
            appFound = null;
        }
        if (action == WindowPlacement.Action.FULLSCREEN && appFullscreen != null) {
            appFullscreen.run();
            appFullscreen = null;
        }
        return action;
    }

    @Override
    public void close() {
        stop();
        SessionBus.close(bus);
    }

    private final class PlacerObject implements Placer {
        @Override
        public String place(String window) {
            try {
                return KWinWindows.this.place(window).name().toLowerCase();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "cannot place window " + window, e);
                return "ignore";
            }
        }

        @Override
        public String getObjectPath() {
            return OBJECT_PATH;
        }
    }
}
