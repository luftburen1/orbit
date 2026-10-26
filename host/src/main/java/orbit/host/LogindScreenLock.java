package orbit.host;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import orbit.protocol.Commands;

/**
 * The session's screen lock through logind's {@code loginctl}: the desktop's locker sets the session's
 * {@code LockedHint} while it's locked, and drops the lock without its password when logind says Unlock. Plasma's
 * kscreenlocker does both. The session's owner may unlock it without root.
 */
final class LogindScreenLock implements HostDesktop.ScreenLock {

    private static final Logger LOG = Logger.getLogger(LogindScreenLock.class.getName());

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** How long the locker gets to drop the lock after logind's Unlock. */
    static final Duration UNLOCK_WAIT = Duration.ofSeconds(3);

    /** Runs {@code loginctl} with its arguments; a fake in tests. */
    interface Loginctl {
        Commands.Result run(List<String> args) throws IOException, InterruptedException;
    }

    private final String session;
    private final Loginctl loginctl;
    private final Duration unlockWait;

    LogindScreenLock(String session, Loginctl loginctl, Duration unlockWait) {
        this.session = session;
        this.loginctl = loginctl;
        this.unlockWait = unlockWait;
    }

    /** Available when {@code loginctl} runs and tells whether this session is locked. */
    static Optional<HostDesktop.ScreenLock> find() {
        // logind's "auto" is the caller's session, or else the user's graphical one: Orbit may be started outside it.
        String id = System.getenv("XDG_SESSION_ID");
        LogindScreenLock lock = new LogindScreenLock(id != null && !id.isBlank() ? id : "auto",
                args -> Commands.run(concat("loginctl", args), TIMEOUT), UNLOCK_WAIT);
        try {
            lock.locked();
            return Optional.of(lock);
        } catch (IOException e) {
            LOG.log(Level.FINE, "cannot read the session's lock from logind", e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    @Override
    public boolean locked() throws IOException, InterruptedException {
        return run("show-session", session, "-p", "LockedHint", "--value").strip().equals("yes");
    }

    @Override
    public void unlock() throws IOException, InterruptedException {
        run("unlock-session", session);
        long deadline = System.nanoTime() + unlockWait.toNanos();
        while (locked()) {
            if (System.nanoTime() > deadline) {
                throw new IOException("the session is still locked " + unlockWait.toMillis() / 1000.0
                        + " s after unlocking it");
            }
            Thread.sleep(100);
        }
    }

    private String run(String... args) throws IOException, InterruptedException {
        Commands.Result result = loginctl.run(List.of(args));
        if (!result.ok()) {
            throw new IOException("loginctl " + args[0] + " failed: " + result.output().strip());
        }
        return result.output();
    }

    private static List<String> concat(String first, List<String> rest) {
        return Stream.concat(Stream.of(first), rest.stream()).toList();
    }
}
