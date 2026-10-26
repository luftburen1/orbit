package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IconsTest {

    @Test
    void streamingDotPulsesBetweenFullAndFaint() {
        assertEquals(1.0f, Icons.pulseAlpha(0), 1e-6);
        assertEquals(0.3f, Icons.pulseAlpha(Icons.PULSE_NANOS / 2), 1e-6);
        assertEquals(1.0f, Icons.pulseAlpha(Icons.PULSE_NANOS * 7), 1e-6, "repeats");
        assertEquals(Icons.pulseAlpha(-Icons.PULSE_NANOS / 4), Icons.pulseAlpha(Icons.PULSE_NANOS * 3 / 4), 1e-6,
                "nanoTime may be negative");
    }

}
