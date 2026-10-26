package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BitrateAdvisorTest {

    @Test
    void matchesMoonlightDefaultsAt60Fps() {
        assertEquals(10_000, BitrateAdvisor.recommendedKbps(1280, 720, 60));
        assertEquals(20_000, BitrateAdvisor.recommendedKbps(1920, 1080, 60));
        assertEquals(40_000, BitrateAdvisor.recommendedKbps(2560, 1440, 60));
        assertEquals(80_000, BitrateAdvisor.recommendedKbps(3840, 2160, 60));
    }

    @Test
    void scalesWithFrameRate() {
        assertEquals(10_000, BitrateAdvisor.recommendedKbps(1920, 1080, 30));
        assertEquals(28_000, BitrateAdvisor.recommendedKbps(1920, 1080, 120), "less than double above 60 fps");
    }

    @Test
    void interpolatesBetweenResolutions() {
        int ultrawide = BitrateAdvisor.recommendedKbps(3440, 1440, 60);
        assertEquals(51_000, ultrawide);
        assertTrue(ultrawide > 40_000 && ultrawide < 80_000);
    }

    @Test
    void staysWithinMoonlightsRange() {
        assertEquals(2_000, BitrateAdvisor.recommendedKbps(320, 240, 60));
        assertEquals(BitrateAdvisor.MAX_KBPS, BitrateAdvisor.recommendedKbps(7680, 4320, 120));
    }

    @Test
    void formats() {
        assertEquals("20 Mbps", BitrateAdvisor.format(20_000));
        assertEquals("4.5 Mbps", BitrateAdvisor.format(4_500));
    }
}
