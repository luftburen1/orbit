package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ScreenUnlockTest {

    /** A screen lock that records what it's asked to do. */
    static final class FakeLock implements HostDesktop.ScreenLock {
        final List<String> events;
        volatile boolean locked;
        volatile boolean failUnlock;

        FakeLock(List<String> events, boolean locked) {
            this.events = events;
            this.locked = locked;
        }

        @Override
        public boolean locked() {
            return locked;
        }

        @Override
        public void unlock() throws IOException {
            events.add("unlock");
            if (failUnlock) {
                throw new IOException("still locked");
            }
            locked = false;
        }
    }

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    private ScreenUnlock unlock(FakeLock lock) {
        return new ScreenUnlock(lock, enabled::get, Duration.ZERO);
    }

    @Test
    void unlocksALockedSessionForAStream() throws Exception {
        FakeLock lock = new FakeLock(events, true);

        unlock(lock).streamStarting();

        assertEquals(List.of("unlock"), events);
        assertFalse(lock.locked);
    }

    @Test
    void leavesAnUnlockedSessionAlone() throws Exception {
        unlock(new FakeLock(events, false)).streamStarting();

        assertEquals(List.of(), events);
    }

    @Test
    void doesNothingUnlessItsSet() throws Exception {
        enabled.set(false);

        unlock(new FakeLock(events, true)).streamStarting();

        assertEquals(List.of(), events);
    }

    @Test
    void aFailedUnlockDoesntStopTheStream() throws Exception {
        FakeLock lock = new FakeLock(events, true);
        lock.failUnlock = true;

        unlock(lock).streamStarting();

        assertEquals(List.of("unlock"), events);
    }
}
