package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KScreenDoctorTest {

    /** The parts of {@code kscreen-doctor -j} that matter, as on a host with the TV on. */
    private static final String JSON = """
            {"outputs": [
              {"name": "HDMI-A-1", "connected": true, "enabled": true, "priority": 1, "currentModeId": "7",
               "pos": {"x": 0, "y": 0}, "size": {"width": 3440, "height": 1440}, "scale": 1, "rotation": 1,
               "modes": []},
              {"name": "DP-1", "connected": true, "enabled": true, "priority": 2, "currentModeId": "65",
               "pos": {"x": 3440, "y": 0}, "size": {"width": 3840, "height": 2160}, "scale": 1.5, "rotation": 1,
               "modes": []},
              {"name": "DP-2", "connected": true, "enabled": true, "priority": 3, "currentModeId": "1",
               "pos": {"x": 0, "y": 1440}, "size": {"width": 1920, "height": 1080}, "scale": 1, "rotation": 2,
               "modes": []},
              {"name": "HDMI-A-2", "connected": true, "enabled": false, "currentModeId": "1",
               "pos": {"x": 0, "y": 0}, "size": {"width": 1920, "height": 1080}, "scale": 1, "rotation": 1,
               "modes": []}
            ]}""";

    @Test
    void boundsAreInTheDesktopsScaledCoordinates() throws IOException {
        assertEquals(Optional.of(new Rectangle(0, 0, 3440, 1440)), KScreenDoctor.bounds(JSON, "HDMI-A-1"));
        assertEquals(Optional.of(new Rectangle(3440, 0, 2560, 1440)), KScreenDoctor.bounds(JSON, "DP-1"));
    }

    @Test
    void aTurnedMonitorSwapsWidthAndHeight() throws IOException {
        assertEquals(Optional.of(new Rectangle(0, 1440, 1080, 1920)), KScreenDoctor.bounds(JSON, "DP-2"));
    }

    @Test
    void noBoundsForMonitorsThatAreOffOrMissing() throws IOException {
        assertEquals(Optional.empty(), KScreenDoctor.bounds(JSON, "HDMI-A-2"));
        assertEquals(Optional.empty(), KScreenDoctor.bounds(JSON, "DP-3"));
    }
}
