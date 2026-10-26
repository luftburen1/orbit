package orbit.agent;

/**
 * The TV remote's keys while the host asks, on the streamed monitor, whether to stop the stream: OK and Cancel side by
 * side, Cancel selected at first. Left and right choose, OK takes the choice, and Back cancels.
 */
final class StopQuestionKeys {

    /** What a press did. */
    enum Result {
        /** Nothing: the key means nothing here. */
        NOTHING,
        /** The selection moved; {@link #okSelected} says where. */
        MOVED,
        /** Answered: stop the stream. */
        STOP,
        /** Answered: keep it. */
        CANCEL
    }

    private boolean okSelected;

    boolean okSelected() {
        return okSelected;
    }

    Result press(RemoteKeys.Key key) {
        return switch (key) {
            case LEFT -> select(true);
            case RIGHT -> select(false);
            case OK -> okSelected ? Result.STOP : Result.CANCEL;
            case BACK -> Result.CANCEL;
            case UP, DOWN, PLAY_PAUSE, STOP -> Result.NOTHING;
        };
    }

    private Result select(boolean ok) {
        if (okSelected == ok) {
            return Result.NOTHING;
        }
        okSelected = ok;
        return Result.MOVED;
    }
}
