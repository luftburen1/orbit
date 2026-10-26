package orbit.agent;

import java.time.Duration;
import java.util.logging.Logger;
import orbit.protocol.Message;

/**
 * A stop at the client, with the TV remote or the tray. If stopping would quit the app on the host, losing anything
 * unsaved, it asks first: on the streamed monitor for the remote (where the host can show it, and the remote's keys
 * answer it), in a dialog for the tray. Then the host stops the stream as its own Stop would, so the app quits
 * unless other clients still stream it. A host that can't tell or can't stop, or doesn't answer in time, gets the stream
 * disconnected here instead.
 *
 * <p>Runs on the agent's worker thread, except {@link #questionOpen}, which the remote's reader thread reads.
 */
final class StopFlow {

    private static final Logger LOG = Logger.getLogger(StopFlow.class.getName());

    /** If the host hasn't stopped the stream this long after the client asked it to, the client ends it. */
    static final Duration STOP_FALLBACK = Duration.ofSeconds(10);
    /** How long the host's "Stopping the stream" notice shows on the TV before the remote's Stop takes effect. */
    static final Duration STOP_NOTICE = Duration.ofSeconds(1);
    /** How long a stop waits for the host to say whether it loses anything, before it stops without asking. */
    static final Duration STOP_CHECK_WAIT = Duration.ofSeconds(5);
    /** The stop question on the streamed monitor closes after this, keeping the stream. */
    static final Duration STOP_QUESTION_TIMEOUT = Duration.ofSeconds(30);

    /** What the stop needs from the agent; called on the worker thread. */
    interface Agent {
        /** Approved by the host and connected to it. */
        boolean hostConnected();

        /** The host takes {@link Message.StopRequest}s. */
        boolean hostStops();

        /** The host answers {@link Message.StopCheckRequest}s. */
        boolean hostChecksStops();

        /** The host shows what the remote does ({@link Message.RemoteAction}). */
        boolean hostShowsActions();

        /** Counts the streams started, so a step that comes later only acts on the stream it was for. */
        long streamNumber();

        boolean streaming();

        void send(Message.FromAgent message);

        /** Runs {@code action} on the worker thread after {@code delay}. */
        void later(Duration delay, Runnable action);

        /** Ends this client's stream, leaving the app running on the host. */
        void disconnect(String how);

        /** The remote's input device is read, so its keys can answer a question on the streamed monitor. */
        boolean remoteFound();

        /** The remote's keys come here (and not to the stream) while the question is open. */
        void takeRemoteForQuestion();

        /** The question closed: the remote's keys go where they went before. */
        void giveRemoteBack();

        /**
         * Asks in a dialog at the client, and calls {@code stop} if the answer is to stop.
         *
         * @return false when there's nowhere to ask (no tray)
         */
        boolean askHere(String warning, Runnable stop);
    }

    /** Where a stop waits for the host's check: whether it's the remote's, and for which stream. */
    private record Wait(boolean remote, long stream) {
        String how() {
            return remote ? "with the TV remote" : "from the tray";
        }
    }

    private final Agent agent;
    /** A stop waits for the host's {@link Message.StopCheck}; else null. */
    private Wait check;
    /** The host asks on the streamed monitor whether to stop, and the remote's keys answer it; else null. */
    private volatile StopQuestionKeys question;
    /** Counts stop questions, so a timeout only closes its own. */
    private long questions;
    /** The remote's Stop waits for its notice to be seen. */
    private boolean noticePending;

    StopFlow(Agent agent) {
        this.agent = agent;
    }

    /** Stop at the client: asks the host first whether that loses anything. */
    void ask(boolean remote) {
        Wait wait = new Wait(remote, agent.streamNumber());
        if (question != null || check != null || noticePending) {
            return;
        }
        if (!agent.hostChecksStops() || !agent.hostConnected()) {
            stopWithout(wait);
            return;
        }
        check = wait;
        // The question on the streamed monitor needs the remote's keys away from the stream.
        agent.send(new Message.StopCheckRequest(remote && agent.remoteFound()));
        agent.later(STOP_CHECK_WAIT, () -> {
            if (check == wait) {
                check = null;
                LOG.info("the Orbit host didn't say what stopping loses; stopping " + wait.how());
                stopWithout(wait);
            }
        });
    }

    /** The host's answer to {@link #ask}. */
    void onStopCheck(Message.StopCheck answer) {
        Wait wait = check;
        check = null;
        if (wait == null || wait.stream() != agent.streamNumber() || !agent.streaming()) {
            if (answer.shown()) {
                agent.send(new Message.RemoteAction(Message.RemoteAction.STOP_QUESTION_CLOSED));
            }
            return;
        }
        if (answer.quitsApp() == null) {
            stopWithout(wait);
        } else if (wait.remote() && answer.shown()) {
            openQuestion();
        } else if (wait.remote() || !agent.askHere(Message.StopCheck.warning(answer.quitsApp()),
                () -> agent.later(Duration.ZERO, () -> stopWithout(wait)))) {
            // Nowhere to ask.
            stopWithout(wait);
        }
    }

    /** A connection to a new host: an answer from the old one won't come. */
    void reset() {
        check = null;
    }

    /** The host shows its question on the streamed monitor; read on the remote's reader thread. */
    boolean questionOpen() {
        return question != null;
    }

    /** A remote key while the question is open. */
    void questionKey(RemoteKeys.Key key) {
        StopQuestionKeys open = question;
        if (open == null) {
            return;
        }
        switch (open.press(key)) {
            case MOVED -> agent.send(new Message.RemoteAction(open.okSelected()
                    ? Message.RemoteAction.STOP_QUESTION_OK : Message.RemoteAction.STOP_QUESTION_CANCEL));
            case STOP -> closeQuestion(true);
            case CANCEL -> closeQuestion(false);
            case NOTHING -> {
            }
        }
    }

    /** Closes the host's stop question, if it's open, and stops the stream if that was the answer. */
    void closeQuestion(boolean stop) {
        if (question == null) {
            return;
        }
        question = null;
        agent.giveRemoteBack();
        agent.send(new Message.RemoteAction(Message.RemoteAction.STOP_QUESTION_CLOSED));
        LOG.info(stop ? "TV remote: stop confirmed" : "TV remote: stop cancelled; the stream goes on");
        if (stop) {
            stopWithNotice("with the TV remote");
        }
    }

    private void openQuestion() {
        LOG.info("TV remote: asking on the streamed monitor whether to stop the stream");
        agent.takeRemoteForQuestion();
        question = new StopQuestionKeys();
        long number = ++questions;
        agent.later(STOP_QUESTION_TIMEOUT, () -> {
            if (questions == number && question != null) {
                LOG.info("TV remote: the stop question timed out; the stream goes on");
                closeQuestion(false);
            }
        });
    }

    /** The stop asked for, without asking first. */
    private void stopWithout(Wait wait) {
        if (wait.stream() != agent.streamNumber() || !agent.streaming()) {
            return;
        }
        if (wait.remote()) {
            stopWithNotice(wait.how());
        } else {
            stop(wait.how());
        }
    }

    /**
     * The remote's Stop: where the host shows what the remote does, it shows "Stopping the stream" first, and the stop
     * follows {@link #STOP_NOTICE} later so the notice is seen before the stream closes.
     */
    private void stopWithNotice(String how) {
        if (noticePending) {
            return;
        }
        if (!agent.hostShowsActions() || !agent.hostConnected()) {
            stop(how);
            return;
        }
        noticePending = true;
        agent.send(new Message.RemoteAction(Message.RemoteAction.STOP));
        long stream = agent.streamNumber();
        agent.later(STOP_NOTICE, () -> {
            noticePending = false;
            if (agent.streamNumber() == stream && agent.streaming()) {
                stop(how);
            }
        });
    }

    /**
     * Has the host stop the stream as its own Stop would. A host that can't, or doesn't within
     * {@link #STOP_FALLBACK}, gets the stream disconnected here instead.
     */
    private void stop(String how) {
        if (!agent.hostStops() || !agent.hostConnected()) {
            agent.disconnect(how);
            return;
        }
        LOG.info("stopping the stream " + how + "; asking the Orbit host");
        agent.send(new Message.StopRequest());
        long stream = agent.streamNumber();
        agent.later(STOP_FALLBACK, () -> {
            // Not a stream started since: the host may well have stopped this one and started another.
            if (agent.streamNumber() == stream && agent.streaming()) {
                agent.disconnect(how + " (the Orbit host didn't stop it)");
            }
        });
    }
}
