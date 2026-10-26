package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MonitorMemoryTest {

    private static final SunshineClient.Monitor DESK = new SunshineClient.Monitor("HDMI-A-4", 0, "Acme UW34");
    private static final SunshineClient.Monitor TV = new SunshineClient.Monitor("DP-2", 1, "Acme TV");

    @Test
    void keepsShowingAMonitorSunshineMissedBecauseItWasOff(@TempDir Path dir) {
        Path file = dir.resolve("monitors.json");
        new MonitorMemory(file).complete(List.of(DESK, TV), Map.of("HDMI-A-4", true, "DP-2", true));

        // Orbit restarted, then Sunshine restarted while the TV was off: Sunshine lists only the desk monitor.
        MonitorMemory later = new MonitorMemory(file);
        assertEquals(List.of(DESK, new SunshineClient.Monitor("DP-2", 1, "Acme TV (off)")),
                later.complete(List.of(DESK), Map.of("HDMI-A-4", true, "DP-2", false)));
    }

    @Test
    void leavesOutMonitorsThatAreUnplugged(@TempDir Path dir) {
        MonitorMemory memory = new MonitorMemory(dir.resolve("monitors.json"));
        memory.complete(List.of(DESK, TV), Map.of("HDMI-A-4", true, "DP-2", true));
        assertEquals(List.of(DESK), memory.complete(List.of(DESK), Map.of("HDMI-A-4", true)),
                "not plugged in, so not offered");
    }

    @Test
    void fillsInMonitorsAnUnfamiliarLogLeftOut(@TempDir Path dir) {
        MonitorMemory memory = new MonitorMemory(dir.resolve("monitors.json"));
        memory.complete(List.of(DESK, TV), Map.of("HDMI-A-4", true, "DP-2", true));
        // A Sunshine update words its log differently: no monitor list at all, but KDE still sees all three.
        assertEquals(List.of(new SunshineClient.Monitor("DP-2", 1, "Acme TV (off)"),
                        new SunshineClient.Monitor("DP-3", -1, "connected"), DESK),
                memory.complete(List.of(), Map.of("HDMI-A-4", true, "DP-2", false, "DP-3", true)).stream()
                        .sorted(java.util.Comparator.comparing(SunshineClient.Monitor::id)).toList());
    }

    @Test
    void addsNothingWhenSunshineHasNeverListedMonitors(@TempDir Path dir) {
        // E.g. Sunshine captures with X11 or the portal, where KDE's connector names don't mean anything to it.
        MonitorMemory memory = new MonitorMemory(dir.resolve("monitors.json"));
        assertEquals(List.of(), memory.complete(List.of(), Map.of("HDMI-A-4", true, "DP-2", false)));
    }
}
