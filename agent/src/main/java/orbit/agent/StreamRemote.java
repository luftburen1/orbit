package orbit.agent;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The TV remote during a stream, when the agent has its input device to itself: in key mode its keys go on to
 * Moonlight through a virtual keyboard (OK as Enter, Back as Escape), and in mouse mode the arrows move a virtual
 * mouse and OK clicks (held for {@link #RIGHT_CLICK_AFTER}: right click). Play/Pause switches between the two;
 * Stop stops the stream.
 *
 * <p>Someone at the host may have clicked elsewhere meanwhile, so the first press after {@link #FOCUS_AFTER} without
 * any asks the host to give focus back to the stream (and the cursor, in mouse mode), and is held back for
 * {@link #FOCUS_WAIT} along with what follows it, so it doesn't land in the host user's window. Entering mouse mode
 * asks for the cursor too.
 */
final class StreamRemote {

    private static final Logger LOG = Logger.getLogger(StreamRemote.class.getName());

    /** Where the keys and the pointer go: the virtual devices, or a fake in tests. */
    interface Output {
        void key(int code, int value) throws IOException;

        void move(int dx, int dy) throws IOException;
    }

    /** What the remote has the agent and the host do. */
    interface Actions {
        /** Stop was pressed. */
        void stop();

        /**
         * Asks the host to give focus back to the stream.
         *
         * @param pointer the cursor should be on the stream too
         * @return true if asked; false where the host can't, so there's nothing to wait for
         */
        boolean focus(boolean pointer);

        /** Play/Pause switched the mode, e.g. for the host to show it. */
        void modeChanged(Mode mode);
    }

    enum Mode { KEYS, MOUSE }

    private enum Direction {
        UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);

        final int dx;
        final int dy;

        Direction(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }
    }

    // input-event-codes.h
    static final int KEY_ESC = 1;
    static final int KEY_ENTER = 28;
    private static final Map<Integer, Direction> ARROWS = Map.of(103, Direction.UP, 108, Direction.DOWN,
            105, Direction.LEFT, 106, Direction.RIGHT);
    private static final Set<Integer> OK = Set.of(352, 353, KEY_ENTER); // KEY_OK, KEY_SELECT, KEY_ENTER

    static final Duration TICK = Duration.ofMillis(16);
    static final Duration RIGHT_CLICK_AFTER = Duration.ofMillis(800);
    /** A held arrow that has sent nothing for this long has been let go; its release got lost. */
    static final Duration HELD_TIMEOUT = Duration.ofMillis(700);
    /** Pixels per tick: the first step, then the speed ramps from slow to fast over {@link #RAMP}. */
    static final int FIRST_STEP = 6;
    static final int SLOW = 2;
    static final int FAST = 20;
    static final Duration RAMP = Duration.ofMillis(2000);
    /** A press after this long without any may find focus elsewhere on the host. */
    static final Duration FOCUS_AFTER = Duration.ofSeconds(3);
    /** How long input waits for the host to move focus back: a round trip and a KWin script. */
    static final Duration FOCUS_WAIT = Duration.ofMillis(300);

    private final Output out;
    private final Actions actions;
    /** Runs the ticks while an arrow is held; null in tests, which call {@link #tick} themselves. */
    private final ScheduledExecutorService ticker;
    private final LongSupplier nanoTime;

    private Mode mode = Mode.KEYS;
    private final EnumSet<Direction> held = EnumSet.noneOf(Direction.class);
    private long heldSince;
    private long lastArrow;
    /** When OK went down in mouse mode, or -1. */
    private long okSince = -1;
    /** Keys passed on as pressed and not yet released, so a mode switch or the stream's end can release them. */
    private final Set<Integer> passedDown = new HashSet<>();
    /** When the remote last sent anything (System.nanoTime); none yet this stream while {@link #quiet}. */
    private long lastInput;
    private boolean quiet = true;
    /** When the cursor was last asked for (System.nanoTime). */
    private long pointerAskedAt;
    /** Events held back while the host moves focus; null while not holding. */
    private ArrayDeque<int[]> holding;
    private ScheduledFuture<?> releasing;
    private ScheduledFuture<?> ticking;
    private boolean outputFailed;

    /**
     * @param ticker runs the mouse ticks and releases held input; null in tests, which call {@link #tick} and
     *               {@link #releaseHeld} themselves
     */
    StreamRemote(Output out, Actions actions, ScheduledExecutorService ticker, LongSupplier nanoTime) {
        this.out = out;
        this.actions = actions;
        this.ticker = ticker;
        this.nanoTime = nanoTime;
    }

    synchronized Mode mode() {
        return mode;
    }

    /** One key event from the remote: {@code value} 1 pressed, 0 released, 2 held. */
    synchronized void event(int code, int value) {
        RemoteKeys.Key key = RemoteKeys.KEYS.get(code);
        if (key == RemoteKeys.Key.STOP) {
            if (value == 1) {
                actions.stop();
            }
            return;
        }
        if (holding != null) {
            holding.add(new int[] {code, value});
            return;
        }
        long now = nanoTime.getAsLong();
        boolean afterPause = quiet || now - lastInput > FOCUS_AFTER.toNanos();
        quiet = false;
        lastInput = now;
        if (value == 1 && afterPause) {
            // The mode this press leaves the remote in decides whether the cursor matters.
            boolean pointer = (mode == Mode.MOUSE) != (key == RemoteKeys.Key.PLAY_PAUSE);
            if (pointer) {
                pointerAskedAt = now;
            }
            if (actions.focus(pointer)) {
                holding = new ArrayDeque<>();
                holding.add(new int[] {code, value});
                if (ticker != null) {
                    releasing = ticker.schedule(this::releaseHeld, FOCUS_WAIT.toMillis(), TimeUnit.MILLISECONDS);
                }
                return;
            }
        }
        dispatch(code, value);
    }

    /** The host has had its time to move focus: passes on what was held back, in order. */
    synchronized void releaseHeld() {
        ArrayDeque<int[]> events = holding;
        holding = null;
        releasing = null;
        if (events != null) {
            for (int[] e : events) {
                dispatch(e[0], e[1]);
            }
        }
    }

    private void dispatch(int code, int value) {
        RemoteKeys.Key key = RemoteKeys.KEYS.get(code);
        if (key == RemoteKeys.Key.PLAY_PAUSE) {
            if (value == 1) {
                switchMode();
            }
            return;
        }
        if (mode == Mode.MOUSE && ARROWS.containsKey(code)) {
            arrow(ARROWS.get(code), value);
        } else if (mode == Mode.MOUSE && OK.contains(code)) {
            ok(value);
        } else {
            pass(passedAs(code), value);
        }
    }

    /** What a key goes on to the host as: OK as Enter and Back as Escape, which apps understand; others as they are. */
    private static int passedAs(int code) {
        if (OK.contains(code)) {
            return KEY_ENTER;
        }
        return RemoteKeys.KEYS.get(code) == RemoteKeys.Key.BACK ? KEY_ESC : code;
    }

    private void switchMode() {
        releaseAll();
        mode = mode == Mode.KEYS ? Mode.MOUSE : Mode.KEYS;
        LOG.info(() -> "TV remote: " + (mode == Mode.MOUSE ? "mouse mode (arrows move, OK clicks)" : "key mode"));
        actions.modeChanged(mode);
        if (mode == Mode.MOUSE) {
            long now = nanoTime.getAsLong();
            if (now - pointerAskedAt > FOCUS_WAIT.toNanos() * 2) {
                pointerAskedAt = now;
                actions.focus(true);
            }
            // A nudge there and back, so the host shows its cursor.
            send(() -> {
                out.move(1, 0);
                out.move(-1, 0);
            });
        }
    }

    private void arrow(Direction d, int value) {
        long now = nanoTime.getAsLong();
        if (value == 0) {
            held.remove(d);
            return;
        }
        lastArrow = now;
        if (value == 1) {
            if (held.isEmpty()) {
                heldSince = now;
            }
            held.add(d);
            send(() -> out.move(d.dx * FIRST_STEP, d.dy * FIRST_STEP));
            startTicking();
        }
    }

    private void ok(int value) {
        long now = nanoTime.getAsLong();
        if (value == 1) {
            okSince = now;
        } else if (value == 0 && okSince >= 0) {
            int button = now - okSince >= RIGHT_CLICK_AFTER.toNanos() ? VirtualInput.BTN_RIGHT : VirtualInput.BTN_LEFT;
            okSince = -1;
            send(() -> {
                out.key(button, 1);
                out.key(button, 0);
            });
        }
    }

    private void pass(int code, int value) {
        if (value == 1) {
            passedDown.add(code);
        } else if (value == 0) {
            passedDown.remove(code);
        }
        send(() -> out.key(code, value));
    }

    /** Moves the pointer for held arrows; every {@link #TICK} while any is held. */
    synchronized void tick() {
        long now = nanoTime.getAsLong();
        if (!held.isEmpty() && now - lastArrow > HELD_TIMEOUT.toNanos()) {
            held.clear();
        }
        if (held.isEmpty()) {
            stopTicking();
            return;
        }
        double ramp = Math.min(1, (double) (now - heldSince) / RAMP.toNanos());
        int step = (int) Math.round(SLOW + (FAST - SLOW) * ramp * ramp);
        int dx = 0;
        int dy = 0;
        for (Direction d : held) {
            dx += d.dx * step;
            dy += d.dy * step;
        }
        int x = dx;
        int y = dy;
        send(() -> out.move(x, y));
    }

    /**
     * The remote's keys go elsewhere for a while (the host's stop question): lets go of everything passed on and drops
     * what's held back, staying in the same mode.
     */
    synchronized void pause() {
        if (releasing != null) {
            releasing.cancel(false);
            releasing = null;
        }
        holding = null;
        releaseAll();
    }

    /** The stream ended: lets go of everything and starts in key mode next time. */
    synchronized void reset() {
        pause();
        mode = Mode.KEYS;
        quiet = true;
    }

    private void releaseAll() {
        for (int code : passedDown) {
            send(() -> out.key(code, 0));
        }
        passedDown.clear();
        held.clear();
        okSince = -1;
        stopTicking();
    }

    private void startTicking() {
        if (ticker != null && ticking == null) {
            ticking = ticker.scheduleAtFixedRate(this::tick, TICK.toMillis(), TICK.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void stopTicking() {
        if (ticking != null) {
            ticking.cancel(false);
            ticking = null;
        }
    }

    private interface Send {
        void run() throws IOException;
    }

    private void send(Send action) {
        try {
            action.run();
            outputFailed = false;
        } catch (IOException e) {
            if (!outputFailed) {
                LOG.log(Level.WARNING, "TV remote: cannot pass a key on to Moonlight", e);
            }
            outputFailed = true;
        }
    }
}
