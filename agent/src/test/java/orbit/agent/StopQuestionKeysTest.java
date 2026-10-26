package orbit.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StopQuestionKeysTest {

    private final StopQuestionKeys question = new StopQuestionKeys();

    @Test
    void cancelIsSelectedAtFirstSoOkKeepsTheStream() {
        assertFalse(question.okSelected());
        assertEquals(StopQuestionKeys.Result.CANCEL, question.press(RemoteKeys.Key.OK));
    }

    @Test
    void leftSelectsOkWhichStops() {
        assertEquals(StopQuestionKeys.Result.MOVED, question.press(RemoteKeys.Key.LEFT));
        assertTrue(question.okSelected());
        assertEquals(StopQuestionKeys.Result.NOTHING, question.press(RemoteKeys.Key.LEFT), "already there");
        assertEquals(StopQuestionKeys.Result.STOP, question.press(RemoteKeys.Key.OK));
    }

    @Test
    void rightGoesBackToCancel() {
        question.press(RemoteKeys.Key.LEFT);
        assertEquals(StopQuestionKeys.Result.MOVED, question.press(RemoteKeys.Key.RIGHT));
        assertFalse(question.okSelected());
        assertEquals(StopQuestionKeys.Result.CANCEL, question.press(RemoteKeys.Key.OK));
    }

    @Test
    void backCancelsWhateverIsSelected() {
        question.press(RemoteKeys.Key.LEFT);
        assertEquals(StopQuestionKeys.Result.CANCEL, question.press(RemoteKeys.Key.BACK));
    }

    @Test
    void stopAgainAndOtherKeysDoNothing() {
        for (RemoteKeys.Key key : new RemoteKeys.Key[] {RemoteKeys.Key.STOP, RemoteKeys.Key.PLAY_PAUSE,
                RemoteKeys.Key.UP, RemoteKeys.Key.DOWN}) {
            assertEquals(StopQuestionKeys.Result.NOTHING, question.press(key), key.name());
        }
    }
}
