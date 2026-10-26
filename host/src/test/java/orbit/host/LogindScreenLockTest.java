package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import orbit.protocol.Commands;
import org.junit.jupiter.api.Test;

class LogindScreenLockTest {

    private final List<String> calls = new CopyOnWriteArrayList<>();
    /** What {@code LockedHint} reads, one answer per call; the last one repeats. */
    private volatile List<String> hints = List.of("no");
    private int hintsRead;

    private LogindScreenLock lock(Duration unlockWait) {
        return new LogindScreenLock("5", args -> {
            calls.add(String.join(" ", args));
            if (args.getFirst().equals("show-session")) {
                return new Commands.Result(0, hints.get(Math.min(hintsRead++, hints.size() - 1)) + "\n");
            }
            return new Commands.Result(0, "");
        }, unlockWait);
    }

    @Test
    void readsTheSessionsLockedHint() throws Exception {
        hints = List.of("yes", "no");
        LogindScreenLock lock = lock(Duration.ofSeconds(1));

        assertTrue(lock.locked());
        assertFalse(lock.locked());
        assertEquals("show-session 5 -p LockedHint --value", calls.getFirst());
    }

    @Test
    void unlockWaitsUntilTheLockerHasDroppedTheLock() throws Exception {
        hints = List.of("yes", "yes", "no");

        lock(Duration.ofSeconds(2)).unlock();

        assertEquals(List.of("unlock-session 5", "show-session 5 -p LockedHint --value",
                "show-session 5 -p LockedHint --value", "show-session 5 -p LockedHint --value"), calls);
    }

    @Test
    void anUnlockTheLockerIgnoresFails() {
        hints = List.of("yes");

        IOException e = assertThrows(IOException.class, () -> lock(Duration.ofMillis(200)).unlock());
        assertEquals("the session is still locked 0.2 s after unlocking it", e.getMessage());
    }

    @Test
    void aFailingLoginctlSaysWhy() {
        LogindScreenLock lock = new LogindScreenLock("5",
                args -> new Commands.Result(1, "Could not unlock session: Access denied\n"), Duration.ZERO);

        IOException e = assertThrows(IOException.class, lock::unlock);
        assertEquals("loginctl unlock-session failed: Could not unlock session: Access denied", e.getMessage());
    }
}
