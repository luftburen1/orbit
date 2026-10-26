package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import orbit.protocol.StreamSettings;
import org.junit.jupiter.api.Test;

class ResolutionPresetTest {

    @Test
    void findsMatchingPreset() {
        assertEquals("1080p", ResolutionPreset.of(1920, 1080).orElseThrow().name());
        assertEquals("3440 × 1440 (21:9 ultrawide)", ResolutionPreset.of(3440, 1440).orElseThrow().toString());
        assertTrue(ResolutionPreset.of(1921, 1080).isEmpty(), "non-preset sizes are custom");
    }

    @Test
    void presetsAreUniqueSortedAndValid() {
        assertEquals(ResolutionPreset.ALL.size(),
                new HashSet<>(ResolutionPreset.ALL.stream().map(p -> p.width() + "x" + p.height()).toList()).size());
        for (int i = 1; i < ResolutionPreset.ALL.size(); i++) {
            ResolutionPreset a = ResolutionPreset.ALL.get(i - 1);
            ResolutionPreset b = ResolutionPreset.ALL.get(i);
            assertTrue(a.width() < b.width() || (a.width() == b.width() && a.height() < b.height()), a + " before " + b);
        }
        // Every preset must be accepted by StreamSettings and the dialog's width/height spinner ranges.
        for (ResolutionPreset p : ResolutionPreset.ALL) {
            new StreamSettings(p.width(), p.height(), 60, 20_000, List.of());
            assertTrue(p.width() >= 320 && p.width() <= 7680 && p.height() >= 240 && p.height() <= 4320, p.toString());
        }
    }

    @Test
    void defaultSettingsMatchAPreset() {
        assertTrue(ResolutionPreset.of(StreamSettings.DEFAULT.width(), StreamSettings.DEFAULT.height()).isPresent());
    }
}
