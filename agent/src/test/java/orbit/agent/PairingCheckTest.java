package orbit.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.Test;

class PairingCheckTest {

    private static final String HOST = "192.0.2.1";

    /** Moonlight's answers, in turn. */
    private final Deque<MoonlightController.Pairing> answers = new ArrayDeque<>();

    private PairingCheck check() {
        return new PairingCheck(host -> {
            MoonlightController.Pairing next = answers.poll();
            if (next == null) {
                throw new IOException("no moonlight");
            }
            return next;
        }, Duration.ofSeconds(25));
    }

    private void answer(MoonlightController.Pairing.Kind kind) {
        answers.add(new MoonlightController.Pairing(kind, kind == MoonlightController.Pairing.Kind.PAIRED
                || kind == MoonlightController.Pairing.Kind.NOT_PAIRED ? null : "trouble"));
    }

    @Test
    void unknownAtFirstAndDue() {
        PairingCheck check = check();
        assertNull(check.paired());
        assertTrue(check.due());
    }

    @Test
    void pairedThenNotDueUntilTheRecheck() throws Exception {
        PairingCheck check = check();
        answer(MoonlightController.Pairing.Kind.PAIRED);
        check.refresh(HOST);
        assertEquals(Boolean.TRUE, check.paired());
        assertFalse(check.due());
    }

    @Test
    void oneUnreachableCheckKeepsTheLastState() throws Exception {
        PairingCheck check = check();
        answer(MoonlightController.Pairing.Kind.PAIRED);
        answer(MoonlightController.Pairing.Kind.UNREACHABLE);
        answer(MoonlightController.Pairing.Kind.UNREACHABLE);
        check.refresh(HOST);
        check.refresh(HOST);
        assertEquals(Boolean.TRUE, check.paired());
        check.refresh(HOST);
        assertNull(check.paired());
        assertTrue(check.problem().contains("can't reach Sunshine on " + HOST));
    }

    @Test
    void noAnswerIsReportedOnlyAfterSeveralInARow() throws Exception {
        PairingCheck check = check();
        answer(MoonlightController.Pairing.Kind.PAIRED);
        check.refresh(HOST);
        for (int i = 1; i < PairingCheck.NO_ANSWER_REPORTED_AFTER; i++) {
            answer(MoonlightController.Pairing.Kind.NO_ANSWER);
            check.refresh(HOST);
            assertEquals(Boolean.TRUE, check.paired());
        }
        answer(MoonlightController.Pairing.Kind.NO_ANSWER);
        check.refresh(HOST);
        assertNull(check.paired());
    }

    @Test
    void moonlightThatCantRunIsAProblem() throws Exception {
        PairingCheck check = check();
        check.refresh(HOST);
        assertNull(check.paired());
        assertTrue(check.problem().startsWith("cannot run Moonlight"));
    }

    @Test
    void beforeStreamRefusesWhenUnpairedOrUnreachable() throws Exception {
        PairingCheck check = check();
        answer(MoonlightController.Pairing.Kind.NOT_PAIRED);
        assertThrows(IllegalStateException.class, () -> check.beforeStream(HOST));
        assertEquals(Boolean.FALSE, check.paired());
        answer(MoonlightController.Pairing.Kind.UNREACHABLE);
        assertThrows(IllegalStateException.class, () -> check.beforeStream(HOST));
        assertNull(check.paired());
    }

    @Test
    void beforeStreamLetsAnUnclearCheckTry() throws Exception {
        PairingCheck check = check();
        answer(MoonlightController.Pairing.Kind.PAIRED);
        check.refresh(HOST);
        answer(MoonlightController.Pairing.Kind.NO_ANSWER);
        check.beforeStream(HOST);
        assertEquals(Boolean.TRUE, check.paired());
    }
}
