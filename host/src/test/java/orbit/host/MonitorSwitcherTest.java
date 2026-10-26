package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import orbit.protocol.ClientState;
import org.junit.jupiter.api.Test;

class MonitorSwitcherTest {

    private final AgentRegistry registry = new AgentRegistry(null, Clock.systemUTC());
    /** Everything that happens, in order, e.g. "disconnect tv", "select HDMI-A-4", "start tv Desktop". */
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final StreamDirector director = new StreamDirector(registry, null, Optional::empty, Duration.ofMillis(10));
    private final MonitorSwitcher switcher = new MonitorSwitcher(registry, director, Duration.ofMillis(10));

    private ScriptedAgent agent(String id, ClientState state, String app) {
        return new ScriptedAgent(registry, events, id, state, app);
    }

    private MonitorSwitcher.Selector selector() {
        return id -> events.add("select " + id);
    }

    @Test
    void disconnectsSwitchesThenReconnectsOnlyStreamingClients() throws Exception {
        agent("tv", ClientState.STREAMING, "Steam Big Picture");
        agent("desk", ClientState.AVAILABLE, null);
        List<String> progress = new CopyOnWriteArrayList<>();

        MonitorSwitcher.Result result = switcher.switchTo("HDMI-A-4", selector(), progress::add);

        assertEquals(List.of("disconnect tv", "select HDMI-A-4", "start tv Steam Big Picture 1920x1080"), events);
        assertNull(result.switchError());
        assertEquals(List.of("tv (Steam Big Picture)"), result.reconnected());
        assertTrue(result.failed().isEmpty());
        assertTrue(progress.getFirst().startsWith("Disconnecting tv"), progress.toString());
    }

    @Test
    void retriesWhileSunshineIsStillStarting() throws Exception {
        ScriptedAgent tv = agent("tv", ClientState.STREAMING, "Desktop");
        tv.failingStarts.set(2);

        MonitorSwitcher.Result result = switcher.switchTo("DP-2", selector(), p -> { });

        assertEquals(3, events.stream().filter(e -> e.startsWith("start")).count());
        assertEquals(List.of("tv (Desktop)"), result.reconnected());
    }

    @Test
    void reportsClientsThatNeverReconnect() throws Exception {
        ScriptedAgent tv = agent("tv", ClientState.STREAMING, "Desktop");
        tv.failingStarts.set(Integer.MAX_VALUE);

        MonitorSwitcher.Result result = switcher.switchTo("DP-2", selector(), p -> { });

        assertEquals(MonitorSwitcher.START_ATTEMPTS, events.stream().filter(e -> e.startsWith("start")).count());
        assertEquals(List.of("tv: host not ready"), result.failed());
    }

    @Test
    void streamStartedOutsideOrbitIsDisconnectedButNotRestarted() throws Exception {
        agent("tv", ClientState.STREAMING, null);

        MonitorSwitcher.Result result = switcher.switchTo("DP-2", selector(), p -> { });

        assertEquals(List.of("disconnect tv", "select DP-2"), events);
        assertTrue(result.failed().getFirst().contains("not started by Orbit"), result.failed().toString());
    }

    @Test
    void failedSwitchStillReconnects() throws Exception {
        agent("tv", ClientState.STREAMING, "Desktop");

        MonitorSwitcher.Result result = switcher.switchTo("DP-2", id -> {
            throw new IOException("Sunshine rejected the username or password");
        }, p -> { });

        assertEquals("Sunshine rejected the username or password", result.switchError());
        assertEquals(List.of("tv (Desktop)"), result.reconnected());
    }

    @Test
    void nothingToDoWithoutStreams() throws Exception {
        agent("desk", ClientState.AVAILABLE, null);
        MonitorSwitcher.Result result = switcher.switchTo("DP-2", selector(), p -> { });
        assertEquals(List.of("select DP-2"), events);
        assertTrue(result.reconnected().isEmpty() && result.failed().isEmpty());
    }
}
