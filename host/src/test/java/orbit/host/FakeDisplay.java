package orbit.host;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two monitors like this host's (HDMI-A-4 is the primary), recording changes as "mode DP-2 1280×720 @ 60 Hz",
 * "enable DP-2" and "disable DP-2".
 */
final class FakeDisplay implements DisplayControl {
    private final List<String> events;
    private final Map<String, Output> outputs = new LinkedHashMap<>();

    FakeDisplay(List<String> events) {
        this.events = events;
        outputs.put("DP-2", new Output("DP-2", true, false, "dp-1080-120", List.of(
                new Mode("dp-2160-60", 3840, 2160, 60),
                new Mode("dp-1440-120", 2560, 1440, 120),
                new Mode("dp-1440-60", 2560, 1440, 60),
                new Mode("dp-1080-120", 1920, 1080, 120),
                new Mode("dp-1080-60", 1920, 1080, 60),
                new Mode("dp-720-60", 1280, 720, 60))));
        outputs.put("HDMI-A-4", new Output("HDMI-A-4", true, true, "hd-1440uw-100", List.of(
                new Mode("hd-1440uw-100", 3440, 1440, 100),
                new Mode("hd-1440uw-60", 3440, 1440, 60),
                new Mode("hd-1080-60", 1920, 1080, 60))));
    }

    String current(String output) {
        Output o = outputs.get(output);
        return o.currentMode().orElseThrow().toString();
    }

    @Override
    public List<Output> outputs() {
        return new ArrayList<>(outputs.values());
    }

    @Override
    public void setMode(String output, String modeId) {
        Output o = outputs.get(output);
        Output changed = new Output(o.name(), o.enabled(), o.primary(), modeId, o.modes());
        outputs.put(output, changed);
        events.add("mode " + output + " " + changed.currentMode().orElseThrow());
    }

    @Override
    public void setEnabled(String output, boolean enabled) {
        Output o = outputs.get(output);
        outputs.put(output, new Output(o.name(), enabled, o.primary(), o.currentModeId(), o.modes()));
        events.add((enabled ? "enable " : "disable ") + output);
    }

    boolean enabled(String output) {
        return outputs.get(output).enabled();
    }
}
