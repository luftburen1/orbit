package orbit.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class StreamRemoteTest {

    private static final int UP = 103;
    private static final int RIGHT = 106;
    private static final int OK = 352;
    private static final int PAUSE = 201; // KEY_PAUSECD, the Play/Pause some TVs send
    private static final int STOP = 166; // KEY_STOPCD
    private static final int RED = 398; // KEY_RED: any other key
    private static final int EXIT = 174; // KEY_EXIT, the Back key some TVs send

    private final List<String> out = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final AtomicInteger stops = new AtomicInteger();
    /** The focus requests sent: "pointer" or "keys". */
    private final List<String> asked = new ArrayList<>();
    /** Whether the fake host can move focus; most tests are about the keys alone. */
    private boolean hostFocuses;
    private final List<StreamRemote.Mode> modes = new ArrayList<>();
    private final StreamRemote remote = new StreamRemote(new StreamRemote.Output() {
        @Override
        public void key(int code, int value) {
            out.add("key " + code + " " + value);
        }

        @Override
        public void move(int dx, int dy) {
            out.add("move " + dx + " " + dy);
        }
    }, new StreamRemote.Actions() {
        @Override
        public void stop() {
            stops.incrementAndGet();
        }

        @Override
        public boolean focus(boolean pointer) {
            asked.add(pointer ? "pointer" : "keys");
            return hostFocuses;
        }

        @Override
        public void modeChanged(StreamRemote.Mode mode) {
            modes.add(mode);
        }
    }, null, now::get);

    private void advance(long millis) {
        now.addAndGet(millis * 1_000_000L);
    }

    private void press(int code) {
        remote.event(code, 1);
        remote.event(code, 0);
    }

    @Test
    void keyModePassesKeysOnWithOkAsEnter() {
        remote.event(UP, 1);
        remote.event(UP, 2);
        remote.event(UP, 0);
        press(OK);
        press(RED);

        assertEquals(List.of("key 103 1", "key 103 2", "key 103 0", "key 28 1", "key 28 0", "key 398 1",
                "key 398 0"), out);
    }

    @Test
    void backGoesOnAsEscapeInBothModes() {
        press(EXIT);
        assertEquals(List.of("key 1 1", "key 1 0"), out, "key mode");

        press(PAUSE);
        out.clear();
        press(EXIT);
        assertEquals(List.of("key 1 1", "key 1 0"), out, "mouse mode");
    }

    @Test
    void stopStopsTheStreamAndIsNotPassedOn() {
        press(STOP);

        assertEquals(1, stops.get());
        assertEquals(List.of(), out);
    }

    @Test
    void zeroStopsTheStreamLikeStop() {
        press(512); // KEY_NUMERIC_0

        assertEquals(1, stops.get());
        assertEquals(List.of(), out);
    }

    @Test
    void playPauseSwitchesToMouseModeAndNudgesTheCursorIntoView() {
        press(PAUSE);

        assertEquals(StreamRemote.Mode.MOUSE, remote.mode());
        assertEquals(List.of("move 1 0", "move -1 0"), out);

        press(PAUSE);
        assertEquals(StreamRemote.Mode.KEYS, remote.mode());
        assertEquals(List.of(StreamRemote.Mode.MOUSE, StreamRemote.Mode.KEYS), modes, "for the host to show");
    }

    @Test
    void fiveSwitchesModesLikePlayPause() {
        press(517); // KEY_NUMERIC_5

        assertEquals(StreamRemote.Mode.MOUSE, remote.mode());
        assertEquals(List.of("move 1 0", "move -1 0"), out, "not passed on as a key");
    }

    @Test
    void firstPressAfterAPauseAsksForFocusAndWaitsForIt() {
        hostFocuses = true;

        remote.event(UP, 1);
        remote.event(UP, 0);
        assertEquals(List.of("keys"), asked, "the stream's first press");
        assertEquals(List.of(), out, "held until focus is back");

        remote.releaseHeld();
        assertEquals(List.of("key 103 1", "key 103 0"), out, "in order");

        out.clear();
        advance(1000);
        press(UP);
        assertEquals(List.of("keys"), asked, "not again while the remote is in use");
        assertEquals(List.of("key 103 1", "key 103 0"), out);

        advance(StreamRemote.FOCUS_AFTER.toMillis() + 1);
        press(UP);
        assertEquals(List.of("keys", "keys"), asked, "someone at the host may have clicked elsewhere meanwhile");
    }

    @Test
    void nothingIsHeldWhereTheHostCannotMoveFocus() {
        press(UP);

        assertEquals(List.of("keys"), asked);
        assertEquals(List.of("key 103 1", "key 103 0"), out);
    }

    @Test
    void mouseModeAsksForTheCursorToo() {
        hostFocuses = true;
        press(PAUSE);
        assertEquals(List.of("pointer"), asked, "Play/Pause after a pause, into mouse mode");
        remote.releaseHeld();
        assertEquals(StreamRemote.Mode.MOUSE, remote.mode());
        assertEquals(List.of("pointer"), asked, "asked once, not again by the mode switch");

        advance(StreamRemote.FOCUS_AFTER.toMillis() + 1);
        remote.event(RIGHT, 1);
        assertEquals(List.of("pointer", "pointer"), asked);
    }

    @Test
    void enteringMouseModeRightAfterKeysAsksForTheCursor() {
        press(UP);
        advance(500);
        press(PAUSE);

        assertEquals(List.of("keys", "pointer"), asked);
    }

    @Test
    void stopIsNeverHeldBack() {
        hostFocuses = true;
        press(UP);
        press(STOP);

        assertEquals(1, stops.get());
    }

    @Test
    void endOfStreamDropsHeldInputAndStartsQuiet() {
        hostFocuses = true;
        press(UP);
        remote.reset();
        remote.releaseHeld();
        assertEquals(List.of(), out);

        press(UP);
        assertEquals(List.of("keys", "keys"), asked, "the next stream's first press asks again");
    }

    @Test
    void heldArrowMovesFasterTheLongerItIsHeld() {
        press(PAUSE);
        out.clear();

        remote.event(RIGHT, 1);
        assertEquals(List.of("move 6 0"), out, "a first step at once");
        out.clear();

        advance(16);
        remote.tick();
        advance(2000);
        remote.event(RIGHT, 2); // auto-repeat: still held
        remote.tick();
        assertEquals(List.of("move 2 0", "move 20 0"), out);
        out.clear();

        remote.event(RIGHT, 0);
        remote.tick();
        assertEquals(List.of(), out, "let go");
    }

    @Test
    void arrowWhoseReleaseGotLostStopsAfterAWhile() {
        press(PAUSE);
        remote.event(UP, 1);
        out.clear();

        advance(StreamRemote.HELD_TIMEOUT.toMillis() + 1);
        remote.tick();

        assertEquals(List.of(), out);
    }

    @Test
    void okClicksAndHeldOkRightClicks() {
        press(PAUSE);
        out.clear();

        press(OK);
        remote.event(OK, 1);
        advance(StreamRemote.RIGHT_CLICK_AFTER.toMillis());
        remote.event(OK, 2);
        remote.event(OK, 0);

        assertEquals(List.of("key 272 1", "key 272 0", "key 273 1", "key 273 0"), out);
    }

    @Test
    void switchingModeOrEndingTheStreamReleasesHeldKeys() {
        remote.event(UP, 1);
        press(PAUSE);
        assertTrue(out.contains("key 103 0"), out.toString());

        out.clear();
        press(PAUSE); // back to keys
        remote.event(RED, 1);
        out.clear();
        remote.reset();
        assertEquals(List.of("key 398 0"), out);
        assertEquals(StreamRemote.Mode.KEYS, remote.mode());
    }
}
