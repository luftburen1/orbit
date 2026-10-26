package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class HostSettingsDialogTest {

    private static final HostSettingsDialog.Supports KDE = new HostSettingsDialog.Supports("KDE Plasma", true, true,
            true, true);

    @Test
    void anUnchangedFormGivesTheSameSettings() {
        HostConfig config = new HostConfig(47901, "den", null,
                new HostConfig.Sunshine("https://localhost:47990", "me", "pw"), new HostConfig.Monitor(true, false),
                new HostConfig.Stream(true, false, true, false, true, true), false);

        assertEquals(config, new HostSettingsDialog.Form(config, KDE, "laptop").config());
    }

    @Test
    void refusesAnAddressThatIsntAWebAddress() {
        HostConfig config = new HostConfig(0, null, null, new HostConfig.Sunshine("localhost:47990", "me", "pw"),
                null, null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new HostSettingsDialog.Form(config, KDE, "laptop").config());
        assertEquals("The Sunshine address needs to be a web address, like https://localhost:47990.", e.getMessage());
    }

    @Test
    void onlyThePortAndNameTakeARestart() {
        HostConfig config = HostConfig.defaults();

        assertEquals("Settings saved.", HostSettingsDialog.savedNote(config, new HostConfig(0, null, "dark", null, null, null)));
        assertEquals("Settings saved. The port and name apply when Orbit starts again.",
                HostSettingsDialog.savedNote(config, new HostConfig(1234, null, null, null, null, null)));
    }
}
