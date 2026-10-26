package orbit.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import orbit.protocol.Message;
import org.junit.jupiter.api.Test;

class StopFlowTest {

    /** An agent whose host takes everything; delayed steps wait in {@link #later} until run. */
    private static final class FakeAgent implements StopFlow.Agent {
        boolean connected = true;
        boolean hostStops = true;
        boolean checksStops = true;
        boolean showsActions = true;
        boolean remoteFound = true;
        boolean hasTray = true;
        long stream = 1;
        boolean streaming = true;
        final List<Message.FromAgent> sent = new ArrayList<>();
        final List<Runnable> later = new ArrayList<>();
        final List<Duration> delays = new ArrayList<>();
        final List<String> disconnects = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        Runnable trayStop;

        @Override
        public boolean hostConnected() {
            return connected;
        }

        @Override
        public boolean hostStops() {
            return hostStops;
        }

        @Override
        public boolean hostChecksStops() {
            return checksStops;
        }

        @Override
        public boolean hostShowsActions() {
            return showsActions;
        }

        @Override
        public long streamNumber() {
            return stream;
        }

        @Override
        public boolean streaming() {
            return streaming;
        }

        @Override
        public void send(Message.FromAgent message) {
            sent.add(message);
        }

        @Override
        public void later(Duration delay, Runnable action) {
            delays.add(delay);
            later.add(action);
        }

        @Override
        public void disconnect(String how) {
            disconnects.add(how);
        }

        @Override
        public boolean remoteFound() {
            return remoteFound;
        }

        @Override
        public void takeRemoteForQuestion() {
            events.add("take");
        }

        @Override
        public void giveRemoteBack() {
            events.add("give back");
        }

        @Override
        public boolean askHere(String warning, Runnable stop) {
            if (!hasTray) {
                return false;
            }
            trayStop = stop;
            return true;
        }

        /** Runs the delayed steps queued so far, in order. */
        void runLater() {
            List<Runnable> due = List.copyOf(later);
            later.clear();
            due.forEach(Runnable::run);
        }

        long count(Class<?> type) {
            return sent.stream().filter(type::isInstance).count();
        }

        List<String> remoteActions() {
            return sent.stream().filter(Message.RemoteAction.class::isInstance)
                    .map(m -> ((Message.RemoteAction) m).action()).toList();
        }
    }

    @Test
    void nothingLostStopsThroughTheHostAfterTheNotice() {
        FakeAgent agent = new FakeAgent();
        StopFlow stops = new StopFlow(agent);
        stops.ask(true);
        assertEquals(new Message.StopCheckRequest(true), agent.sent.getFirst());
        stops.onStopCheck(new Message.StopCheck(null, false));
        assertEquals(List.of(Message.RemoteAction.STOP), agent.remoteActions());
        assertEquals(0, agent.count(Message.StopRequest.class), "the notice shows first");
        agent.runLater(); // the check's timeout, which finds it answered, and the notice
        assertEquals(1, agent.count(Message.StopRequest.class));
        agent.streaming = false; // the host stopped it
        agent.runLater(); // the fallback
        assertEquals(List.of(), agent.disconnects);
    }

    @Test
    void remoteQuestionOnTheStreamedMonitorIsAnsweredWithTheRemote() {
        FakeAgent agent = new FakeAgent();
        StopFlow stops = new StopFlow(agent);
        stops.ask(true);
        stops.onStopCheck(new Message.StopCheck("Steam", true));
        assertTrue(stops.questionOpen());
        assertEquals(List.of("take"), agent.events);
        stops.questionKey(RemoteKeys.Key.LEFT);
        assertEquals(List.of(Message.RemoteAction.STOP_QUESTION_OK), agent.remoteActions());
        stops.questionKey(RemoteKeys.Key.OK);
        assertFalse(stops.questionOpen());
        assertEquals(List.of("take", "give back"), agent.events);
        assertEquals(List.of(Message.RemoteAction.STOP_QUESTION_OK, Message.RemoteAction.STOP_QUESTION_CLOSED,
                Message.RemoteAction.STOP), agent.remoteActions());
    }

    @Test
    void cancelledQuestionKeepsTheStream() {
        FakeAgent agent = new FakeAgent();
        StopFlow stops = new StopFlow(agent);
        stops.ask(true);
        stops.onStopCheck(new Message.StopCheck("Steam", true));
        stops.questionKey(RemoteKeys.Key.OK); // Cancel is selected at first
        assertFalse(stops.questionOpen());
        agent.runLater();
        assertEquals(0, agent.count(Message.StopRequest.class));
        assertEquals(List.of(), agent.disconnects);
    }

    @Test
    void questionTimesOutKeepingTheStream() {
        FakeAgent agent = new FakeAgent();
        StopFlow stops = new StopFlow(agent);
        stops.ask(true);
        stops.onStopCheck(new Message.StopCheck("Steam", true));
        agent.runLater();
        assertFalse(stops.questionOpen());
        assertTrue(agent.delays.contains(StopFlow.STOP_QUESTION_TIMEOUT));
        assertEquals(0, agent.count(Message.StopRequest.class));
    }

    @Test
    void trayAsksInADialog() {
        FakeAgent agent = new FakeAgent();
        StopFlow stops = new StopFlow(agent);
        stops.ask(false);
        assertEquals(new Message.StopCheckRequest(false), agent.sent.getFirst());
        stops.onStopCheck(new Message.StopCheck("Steam", false));
        agent.trayStop.run();
        agent.runLater();
        agent.runLater();
        assertEquals(1, agent.count(Message.StopRequest.class));
        assertEquals(List.of(), agent.remoteActions(), "no notice for the tray");
    }

    @Test
    void remoteWithoutQuestionOnTheMonitorStops() {
        FakeAgent agent = new FakeAgent();
        StopFlow stops = new StopFlow(agent);
        stops.ask(true);
        stops.onStopCheck(new Message.StopCheck("Steam", false));
        assertFalse(stops.questionOpen());
        assertEquals(List.of(Message.RemoteAction.STOP), agent.remoteActions());
    }

    @Test
    void hostThatDoesntAnswerGetsTheStopAnyway() {
        FakeAgent agent = new FakeAgent();
        agent.showsActions = false;
        StopFlow stops = new StopFlow(agent);
        stops.ask(false);
        agent.runLater();
        assertEquals(1, agent.count(Message.StopRequest.class));
    }

    @Test
    void olderHostOrNoHostDisconnectsHere() {
        FakeAgent agent = new FakeAgent();
        agent.checksStops = false;
        agent.hostStops = false;
        new StopFlow(agent).ask(false);
        assertEquals(List.of("from the tray"), agent.disconnects);

        FakeAgent offline = new FakeAgent();
        offline.connected = false;
        new StopFlow(offline).ask(true);
        assertEquals(List.of("with the TV remote"), offline.disconnects);
        assertEquals(List.of(), offline.sent);
    }

    @Test
    void hostThatDoesntStopItGetsItDisconnected() {
        FakeAgent agent = new FakeAgent();
        agent.checksStops = false;
        agent.showsActions = false;
        new StopFlow(agent).ask(true);
        assertEquals(1, agent.count(Message.StopRequest.class));
        agent.runLater();
        assertEquals(List.of("with the TV remote (the Orbit host didn't stop it)"), agent.disconnects);
    }

    @Test
    void answerForAStreamThatEndedClosesTheHostsQuestion() {
        FakeAgent agent = new FakeAgent();
        StopFlow stops = new StopFlow(agent);
        stops.ask(true);
        agent.stream = 2;
        stops.onStopCheck(new Message.StopCheck("Steam", true));
        assertFalse(stops.questionOpen());
        assertEquals(List.of(Message.RemoteAction.STOP_QUESTION_CLOSED), agent.remoteActions());
    }

    @Test
    void secondStopWhileOneIsUnderwayIsIgnored() {
        FakeAgent agent = new FakeAgent();
        StopFlow stops = new StopFlow(agent);
        stops.ask(true);
        stops.ask(true);
        assertEquals(1, agent.count(Message.StopCheckRequest.class));
        assertInstanceOf(Message.StopCheckRequest.class, agent.sent.getFirst());
    }
}
