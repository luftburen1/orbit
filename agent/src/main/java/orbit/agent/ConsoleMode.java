package orbit.agent;

import java.nio.file.Path;

/**
 * Switches a Linux text console between text and graphics mode ({@code KDSETMODE}). In graphics mode the kernel draws
 * nothing on it, so no blinking cursor or console text appears over the idle screen; X11 and moonlight-qt do the
 * same. Allowed for the console the agent was started from (its controlling terminal).
 */
final class ConsoleMode {

    private static final long KDSETMODE = 0x4B3A;
    private static final long KD_TEXT = 0;
    private static final long KD_GRAPHICS = 1;

    private ConsoleMode() {
    }

    /** @return true if the console is now in that mode; false if it isn't a console, or this isn't allowed */
    static boolean setGraphics(Path console, boolean graphics) {
        try {
            int fd = Libc.open(console.toString(), Libc.O_RDWR | Libc.O_NOCTTY);
            if (fd < 0) {
                return false;
            }
            try {
                return Libc.ioctl(fd, KDSETMODE, graphics ? KD_GRAPHICS : KD_TEXT);
            } finally {
                Libc.close(fd);
            }
        } catch (LinkageError | RuntimeException e) {
            // Not Linux, or native access unavailable: the caller falls back to hiding the cursor.
            return false;
        }
    }
}
