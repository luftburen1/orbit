package orbit.host;

import java.io.IOException;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Unlocks a locked session for a stream when Settings ▸ Stream says so. Locked, the stream would show the lock screen,
 * with the app's windows opening behind it. It isn't locked again afterwards: the desktop locks it once it's idle.
 */
final class ScreenUnlock implements StreamDirector.ScreenUnlocker {

    private static final Logger LOG = Logger.getLogger(ScreenUnlock.class.getName());

    /**
     * How long the desktop gets after unlocking, before windows are placed: it gives focus back to the window that
     * had it, which would otherwise be taken for the app's first window.
     */
    static final Duration FOCUS_SETTLE = Duration.ofMillis(500);

    private final HostDesktop.ScreenLock lock;
    private final BooleanSupplier enabled;
    private final Duration settle;

    ScreenUnlock(HostDesktop.ScreenLock lock, BooleanSupplier enabled) {
        this(lock, enabled, FOCUS_SETTLE);
    }

    ScreenUnlock(HostDesktop.ScreenLock lock, BooleanSupplier enabled, Duration settle) {
        this.lock = lock;
        this.enabled = enabled;
        this.settle = settle;
    }

    @Override
    public void streamStarting() throws InterruptedException {
        if (!enabled.getAsBoolean()) {
            return;
        }
        try {
            if (!lock.locked()) {
                return;
            }
            lock.unlock();
            LOG.info("unlocked the session for a stream");
            Thread.sleep(settle);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "cannot unlock the session for a stream", e);
        }
    }
}
