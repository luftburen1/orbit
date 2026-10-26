package orbit.host;

import java.io.IOException;
import java.util.Optional;

/**
 * What Orbit can do with the host's desktop beyond streaming, which differs by OS and desktop environment. Every part
 * is optional: where Orbit has no integration, streams work the same, without these extras. {@link KdePlasmaDesktop}
 * is the only integration so far; another desktop or OS gets its own class and a line in {@link #detect}.
 */
interface HostDesktop extends AutoCloseable {

    /** What streams get on a desktop Orbit has no integration for: nothing beyond the stream itself. */
    HostDesktop NONE = new HostDesktop() {
        @Override
        public String name() {
            return "no desktop integration";
        }
    };

    /** The desktop's name for the log, e.g. "KDE Plasma". */
    String name();

    /** Changes monitor modes and turns monitors on and off: resolution matching and turning Sunshine's monitor off. */
    default Optional<DisplayControl> display() {
        return Optional.empty();
    }

    /** Puts a stream's windows on Sunshine's monitor, and focus and the cursor with them. */
    default Optional<Windows> windows() {
        return Optional.empty();
    }

    /** Shows short notices on Sunshine's monitor, so they appear in the stream. */
    default Optional<Notices> notices() {
        return Optional.empty();
    }

    /** The desktop's colour scheme, which Orbit's light and dark themes take on when it's of the same kind. */
    default Optional<DesktopColors.Source> colors() {
        return Optional.empty();
    }

    /** Unlocks the session for a stream. */
    default Optional<ScreenLock> screenLock() {
        return Optional.empty();
    }

    @Override
    default void close() {
    }

    /** The integration for the desktop Orbit runs on, or {@link #NONE}. */
    static HostDesktop detect() {
        return KdePlasmaDesktop.detect().orElse(NONE);
    }

    /**
     * A stream's windows, on the monitor Sunshine streams ({@code output}, a name like {@code DP-1}). Implementations
     * also put {@link LaunchSplash}'s window (known by its {@link LaunchSplash#TITLE}) fullscreen on that monitor,
     * below every other window and unfocused, while an app's windows are placed, and {@link StartNotice}'s (by its
     * {@link StartNotice#TITLE}) at the top of the main monitor, above every other window and unfocused.
     */
    interface Windows {
        /**
         * A stream of an app that starts something: from now until {@link #stop}, the app's windows go to
         * {@code output}, starting with its first one, and that monitor gets focus until it comes.
         *
         * @param appFound      told once, when the app's first window is placed: windows opened at the host from then
         *                      on stay where they are
         * @param appFullscreen told once, when one of the app's windows is made fullscreen there: it has started
         * @return false if placing runs already (another client joined the stream) or couldn't start
         */
        boolean placeApp(String output, Runnable appFound, Runnable appFullscreen);

        /** A stream of the desktop itself: gives focus to the desktop on {@code output}, and the cursor. */
        void focusDesktop(String output);

        /** The stream ended, or its app is about to quit: windows stay where they are from now on. */
        void stop();

        /**
         * A client's remote is about to send keys or move the mouse: focus back on the stream's monitor if it's off,
         * and with {@code pointer} the cursor too. Nothing while nothing streams.
         */
        void refocus(boolean pointer);

        /** Hides the cursor while it's idle (the remote's key mode), or stops hiding it. Only while a stream runs. */
        void hideIdleCursor(boolean hide);
    }

    /** The session's screen lock, which covers every monitor, Sunshine's too. */
    interface ScreenLock {
        /** Whether the session is locked now. */
        boolean locked() throws IOException, InterruptedException;

        /** Unlocks the session without its password; returns once it's unlocked. */
        void unlock() throws IOException, InterruptedException;
    }

    /** Short notices on Sunshine's monitor. */
    interface Notices {
        /** Shows what a client's remote did, one of the {@link orbit.protocol.Message.RemoteAction} constants. */
        void remoteAction(String action);

        /**
         * Asks on {@code output} whether to stop a client's stream, as {@link StopQuestion} does: OK or Cancel,
         * Cancel selected. The client's remote answers it, through {@link #selectStop} and {@link #closeStopQuestion}.
         *
         * @param quitsApp the app a stop quits, or empty if unknown
         * @return false if it can't be shown there
         */
        default boolean askToStop(String output, String quitsApp) {
            return false;
        }

        /** Selects OK (stop) or Cancel in the question; nothing if none shows. */
        default void selectStop(boolean ok) {
        }

        /** Closes the question; nothing if none shows. */
        default void closeStopQuestion() {
        }
    }
}
