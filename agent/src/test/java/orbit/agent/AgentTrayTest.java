package orbit.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import orbit.tray.TrayMenu.Entry;
import orbit.tray.TrayMenu.Item;
import orbit.tray.TrayMenu.Separator;
import org.junit.jupiter.api.Test;

class AgentTrayTest {

    private final List<String> calls = new ArrayList<>();
    private final AgentTray.Actions actions = new AgentTray.Actions() {
        @Override
        public void stopStream() {
            calls.add("stop");
        }

        @Override
        public void setStreamsAllowed(boolean allowed) {
            calls.add("allowed " + allowed);
        }

        @Override
        public void showIdleScreen() {
            calls.add("show idle");
        }

        @Override
        public void setIdleScreenAtStartup(boolean show) {
            calls.add("idle at startup " + show);
        }

        @Override
        public void quit() {
            calls.add("quit");
        }
    };

    private static List<String> labels(List<Entry> menu) {
        return menu.stream().map(e -> e instanceof Item i ? i.label() : e instanceof Separator ? "---" : "?").toList();
    }

    private static Item item(List<Entry> menu, String label) {
        return menu.stream().filter(e -> e instanceof Item i && i.label().equals(label)).map(e -> (Item) e)
                .findFirst().orElseThrow(() -> new AssertionError("no " + label + " in " + labels(menu)));
    }

    @Test
    void idleMenuOffersToTurnStreamsOff() {
        List<Entry> menu = AgentTray.menu(new AgentTray.State("Ready to stream", false, true, false), actions);
        assertEquals(List.of("Ready to stream", "---", "Show idle screen", "Don't allow streams", "---",
                "Show idle screen at startup", "---", "Quit"), labels(menu));
        item(menu, "Show idle screen").action().run();
        item(menu, "Don't allow streams").action().run();
        item(menu, "Show idle screen at startup").action().run();
        item(menu, "Quit").action().run();
        assertEquals(List.of("show idle", "allowed false", "idle at startup true", "quit"), calls);
        assertEquals(Boolean.FALSE, item(menu, "Show idle screen at startup").checked());
    }

    @Test
    void streamingMenuCanStopAndTurnStreamsBackOn() {
        List<Entry> menu = AgentTray.menu(new AgentTray.State("Streaming Desktop", true, false, true), actions);
        assertEquals(List.of("Streaming Desktop", "---", "Stop stream", "Allow streams again", "---",
                "Show idle screen at startup", "---", "Quit"), labels(menu));
        item(menu, "Stop stream").action().run();
        item(menu, "Allow streams again").action().run();
        item(menu, "Show idle screen at startup").action().run();
        assertEquals(List.of("stop", "allowed true", "idle at startup false"), calls);
        assertEquals(Boolean.TRUE, item(menu, "Show idle screen at startup").checked());
    }

    @Test
    void statusLineNamesTheAppWhileStreaming() {
        assertEquals("Streaming Desktop", OrbitAgent.trayText("Streaming", true, "Desktop"));
        assertEquals("Streaming", OrbitAgent.trayText("Streaming", true, null), "started outside Orbit");
        assertEquals("Not allowing streams", OrbitAgent.trayText("Not allowing streams", false, null));
    }
}
