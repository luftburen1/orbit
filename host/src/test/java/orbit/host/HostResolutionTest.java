package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import orbit.protocol.StreamSettings;
import org.junit.jupiter.api.Test;

class HostResolutionTest {

    private final List<String> events = new ArrayList<>();
    private final FakeDisplay display = new FakeDisplay(events);
    private final HostResolution resolution = new HostResolution(display);

    private static StreamSettings settings(int w, int h, int fps) {
        return new StreamSettings(w, h, fps, 20_000, List.of());
    }

    private static DisplayControl.Mode mode(int w, int h, double hz) {
        return new DisplayControl.Mode(w + "x" + h + "@" + hz, w, h, hz);
    }

    @Test
    void picksExactSizeWithClosestRefresh() {
        List<DisplayControl.Mode> modes = List.of(mode(1920, 1080, 60), mode(1920, 1080, 59.94), mode(1920, 1080, 120),
                mode(2560, 1440, 60));
        assertEquals(mode(1920, 1080, 60), HostResolution.choose(modes, 1920, 1080, 60).orElseThrow().mode());
        assertEquals(mode(1920, 1080, 120), HostResolution.choose(modes, 1920, 1080, 144).orElseThrow().mode());
        assertEquals(mode(1920, 1080, 120), HostResolution.choose(modes, 1920, 1080, 90).orElseThrow().mode(),
                "tie between 60 and 120 for 90 fps goes to the higher rate");
        assertTrue(HostResolution.choose(modes, 1920, 1080, 60).orElseThrow().exact());
    }

    @Test
    void unsupportedSizeFallsBackToSameAspectThenClosest() {
        // The TV has no 21:9 modes; the desk monitor does.
        List<DisplayControl.Mode> tv = List.of(mode(3840, 2160, 60), mode(2560, 1440, 60), mode(1920, 1080, 60));
        HostResolution.Choice c = HostResolution.choose(tv, 3440, 1440, 60).orElseThrow();
        assertFalse(c.exact());
        assertEquals(2560, c.mode().width(), "closest size when no mode has the aspect ratio");

        List<DisplayControl.Mode> monitor = List.of(mode(3440, 1440, 60), mode(2560, 1080, 60), mode(1920, 1080, 60));
        assertEquals(mode(2560, 1080, 60), HostResolution.choose(monitor, 2560, 1080, 60).orElseThrow().mode());
        assertEquals(mode(1920, 1080, 60), HostResolution.choose(
                List.of(mode(3840, 2160, 60), mode(1920, 1080, 60)), 1600, 900, 60).orElseThrow().mode(),
                "16:9 request picks the nearest 16:9 mode");
        assertTrue(HostResolution.choose(List.of(), 1920, 1080, 60).isEmpty());
    }

    @Test
    void appliesAndRestoresOriginalMode() throws Exception {
        String note = resolution.apply("DP-2", settings(1280, 720, 60));
        assertEquals("DP-2 set to 1280×720 @ 60 Hz", note);
        resolution.apply("DP-2", settings(2560, 1440, 120));
        resolution.restore();
        assertEquals("1920×1080 @ 120 Hz", display.current("DP-2"), "original mode, not the intermediate one");
        assertFalse(resolution.changed());
    }

    @Test
    void notesClosestMode() throws Exception {
        String note = resolution.apply("DP-2", settings(3440, 1440, 60));
        assertTrue(note.contains("closest it supports to 3440×1440"), note);
    }

    @Test
    void doesNothingWhenAlreadyInMode() throws Exception {
        resolution.apply("DP-2", settings(1920, 1080, 120));
        assertTrue(events.isEmpty(), events.toString());
    }

    @Test
    void switchingMonitorRestoresThePreviousOne() throws Exception {
        resolution.apply("DP-2", settings(1280, 720, 60));
        resolution.apply("HDMI-A-4", settings(1920, 1080, 60));
        assertEquals("1920×1080 @ 120 Hz", display.current("DP-2"));
        assertEquals("1920×1080 @ 60 Hz", display.current("HDMI-A-4"));
        resolution.restore();
        assertEquals("3440×1440 @ 100 Hz", display.current("HDMI-A-4"));
    }

    @Test
    void parsesKScreenDoctorJson() throws Exception {
        String json = """
                {"outputs":[
                  {"name":"HDMI-A-4","connected":true,"enabled":true,"priority":1,"currentModeId":"7","modes":[
                    {"id":"1","name":"3440x1440@60","refreshRate":59.973,"size":{"width":3440,"height":1440}},
                    {"id":"7","name":"3440x1440@100","refreshRate":99.982,"size":{"width":3440,"height":1440}}]},
                  {"name":"DP-2","connected":true,"enabled":false,"priority":2,"currentModeId":"","modes":[]},
                  {"name":"DP-1","connected":false,"enabled":false,"modes":[]}
                ]}
                """;
        List<DisplayControl.Output> outputs = KScreenDoctor.parse(json);
        assertEquals(2, outputs.size(), "disconnected outputs are skipped");
        DisplayControl.Output o = outputs.getFirst();
        assertTrue(o.primary(), "priority 1 is the primary monitor");
        assertFalse(outputs.get(1).primary());
        assertFalse(outputs.get(1).enabled());
        assertEquals("HDMI-A-4", o.name());
        assertEquals("3440×1440 @ 100 Hz", o.currentMode().orElseThrow().toString());
        assertEquals(2, o.modes().size());
    }
}
