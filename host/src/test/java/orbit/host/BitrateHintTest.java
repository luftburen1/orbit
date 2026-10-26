package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import org.junit.jupiter.api.Test;

class BitrateHintTest {

    private final JSpinner width = new JSpinner(new SpinnerNumberModel(1920, 320, 7680, 1));
    private final JSpinner height = new JSpinner(new SpinnerNumberModel(1080, 240, 4320, 1));
    private final JSpinner fps = new JSpinner(new SpinnerNumberModel(60, 10, 480, 1));
    private final JSpinner bitrate = new JSpinner(new SpinnerNumberModel(20_000, 500, 500_000, 500));

    private SettingsDialog.BitrateHint hint() {
        return new SettingsDialog.BitrateHint(width, height, fps, bitrate);
    }

    @Test
    void followsRecommendationWhileUntouched() {
        hint();
        width.setValue(2560);
        height.setValue(1440);
        assertEquals(40_000, bitrate.getValue());
        fps.setValue(30);
        assertEquals(20_000, bitrate.getValue());
    }

    @Test
    void customBitrateIsKept() {
        hint();
        bitrate.setValue(35_000);
        width.setValue(2560);
        height.setValue(1440);
        assertEquals(35_000, bitrate.getValue());
    }

    @Test
    void startsCustomWhenSavedBitrateIsNotTheRecommendation() {
        bitrate.setValue(15_000);
        hint();
        width.setValue(1280);
        assertEquals(15_000, bitrate.getValue());
    }

    @Test
    void warnsAboutSunshineCap() {
        SettingsDialog.BitrateHint hint = hint();
        hint.setSunshineCap(20_000);
        assertEquals(" ", hint.capWarning.getText(), "1080p60 fits under 20 Mbps");
        width.setValue(2560);
        height.setValue(1440);
        assertTrue(hint.capWarning.getText().contains("has no effect"), hint.capWarning.getText());
    }
}
