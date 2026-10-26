package orbit.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Decides, for one stream, which windows go to Sunshine's monitor: the first window that opens or gets focus after the
 * stream starts is the app's, and from then on new windows of the app's process family follow it there, e.g. games
 * started from Steam Big Picture. Windows as big as the monitor are made fullscreen, so no title bar shows. A placed
 * window that leaves the monitor again is put back: Wine games (Zwift) size their window to the primary monitor's work
 * area right after it opens.
 *
 * <p>The family is the window's process and everything else under its top process, the one right below the user's
 * service manager: {@code steam.sh} for Steam (which runs before the stream; Sunshine only tells it to open Big
 * Picture), or Sunshine itself for an app it started.
 */
final class WindowPlacement {

    private static final Logger LOG = Logger.getLogger(WindowPlacement.class.getName());

    /** How long after the stream starts its app's first window may appear; a later one is somebody else's. */
    static final Duration FIRST_WINDOW_WITHIN = Duration.ofSeconds(45);

    /** Reads the process tree; {@code /proc} on Linux, a fake in tests. */
    interface Processes {
        OptionalLong parent(long pid);

        Optional<String> name(long pid);

        /** The user's service manager, which starts apps and the desktop: a family ends below it. */
        default boolean isServiceManager(long pid) {
            return name(pid).map("systemd"::equals).orElse(false);
        }

        Processes PROC = new Processes() {
            @Override
            public OptionalLong parent(long pid) {
                try {
                    // "pid (comm) state ppid ...": comm may contain spaces and parentheses, so read after the last ')'.
                    String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
                    String[] rest = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
                    return OptionalLong.of(Long.parseLong(rest[1]));
                } catch (IOException | RuntimeException e) {
                    return OptionalLong.empty();
                }
            }

            @Override
            public Optional<String> name(long pid) {
                try {
                    return Optional.of(Files.readString(Path.of("/proc", Long.toString(pid), "comm")).strip());
                } catch (IOException e) {
                    return Optional.empty();
                }
            }
        };
    }

    enum Action { IGNORE, MOVE, FULLSCREEN }

    /** What happened to a window: it opened, got focus, or left Sunshine's monitor after the script placed it. */
    enum Event { ADDED, ACTIVATED, LEFT }

    /**
     * A window the window manager told about (on KDE, {@code place-windows.js} in KWin).
     *
     * @param width    its size with title bar and borders
     * @param onTarget it's on Sunshine's monitor already
     */
    record Window(Event event, long pid, int width, int height, boolean fullScreen, boolean onTarget,
            int targetWidth, int targetHeight) {

        /** Parses what the window manager sends: "added|activated|left pid width height fullScreen onTarget tw th". */
        static Window parse(String text) {
            String[] f = text.strip().split("\\s+");
            if (f.length != 8) {
                throw new IllegalArgumentException("expected 8 fields: " + text);
            }
            return new Window(Event.valueOf(f[0].toUpperCase(Locale.ROOT)), Long.parseLong(f[1]),
                    Integer.parseInt(f[2]), Integer.parseInt(f[3]), Boolean.parseBoolean(f[4]),
                    Boolean.parseBoolean(f[5]), Integer.parseInt(f[6]), Integer.parseInt(f[7]));
        }

        /** As big as the monitor or bigger: a game or a fullscreen app, not a dialog or launcher. */
        boolean fillsTarget() {
            return width >= targetWidth && height >= targetHeight;
        }
    }

    private final Processes processes;
    /**
     * Processes that are the desktop itself, not an app, given by the desktop's integration: windows started from them
     * don't make a family, or everything opened from the desktop's menu would follow.
     */
    private final Set<String> shellProcesses;
    /** This host's own process: its window, where Start was just clicked, is never the app's. */
    private final long ownPid;
    private final LongSupplier nanoTime;
    private final long startedAt;
    /** The app's process family, by its top process; null until its first window. */
    private Long family;

    WindowPlacement(Processes processes, Set<String> shellProcesses, long ownPid, LongSupplier nanoTime) {
        this.processes = processes;
        this.shellProcesses = shellProcesses;
        this.ownPid = ownPid;
        this.nanoTime = nanoTime;
        this.startedAt = nanoTime.getAsLong();
    }

    synchronized Action decide(Window w) {
        if (w.pid() <= 0 || w.pid() == ownPid) {
            return Action.IGNORE;
        }
        if (w.event() == Event.LEFT) {
            // The script only reports windows Orbit placed; the check keeps a stale report from moving anything else.
            if (family == null || !inFamily(w.pid())) {
                return Action.IGNORE;
            }
            LOG.info(() -> "a window of " + describe(w.pid()) + " left Sunshine's monitor; moving it back");
            return place(w);
        }
        if (family == null) {
            if (nanoTime.getAsLong() - startedAt > FIRST_WINDOW_WITHIN.toNanos()
                    || processes.name(w.pid()).map(shellProcesses::contains).orElse(false)) {
                return Action.IGNORE;
            }
            family = familyOf(w.pid());
            LOG.info(() -> "stream app's window: process " + w.pid() + " (" + describe(w.pid())
                    + "); new windows of " + describe(family) + " go to Sunshine's monitor");
            return place(w);
        }
        // After the first window, only new ones: focus moving between windows the family already had means nothing.
        if (w.event() != Event.ADDED || !inFamily(w.pid())) {
            return Action.IGNORE;
        }
        LOG.info(() -> "moving a new window of " + describe(w.pid()) + " to Sunshine's monitor");
        return place(w);
    }

    private static Action place(Window w) {
        return w.fullScreen() || w.fillsTarget() ? Action.FULLSCREEN : Action.MOVE;
    }

    /** The top process of {@code pid}'s family: its last ancestor below the service manager, or {@code pid}. */
    long familyOf(long pid) {
        long top = pid;
        long p = pid;
        while (true) {
            OptionalLong parent = processes.parent(p);
            if (parent.isEmpty() || parent.getAsLong() <= 1 || processes.isServiceManager(parent.getAsLong())) {
                break;
            }
            p = parent.getAsLong();
            if (processes.name(p).map(shellProcesses::contains).orElse(false)) {
                break;
            }
            top = p;
        }
        return top;
    }

    private boolean inFamily(long pid) {
        for (long p = pid; p > 1; ) {
            if (p == family) {
                return true;
            }
            OptionalLong parent = processes.parent(p);
            if (parent.isEmpty()) {
                return false;
            }
            p = parent.getAsLong();
        }
        return false;
    }

    private String describe(long pid) {
        return processes.name(pid).orElse("process " + pid);
    }
}
