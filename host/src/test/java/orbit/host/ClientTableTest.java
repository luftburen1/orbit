package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import orbit.protocol.ClientState;
import orbit.protocol.StreamSettings;
import org.junit.jupiter.api.Test;

class ClientTableTest {

    @Test
    void settingsShowTheCodecOnlyWhenOneIsChosen() {
        assertEquals("1920x1080 @ 60 fps, 20.0 Mbps", ClientTable.settingsText(StreamSettings.DEFAULT));
        assertEquals("3840x2160 @ 60 fps, 80.0 Mbps, HEVC",
                ClientTable.settingsText(new StreamSettings(3840, 2160, 60, 80_000, List.of(), "HEVC")));
        assertEquals("1920x1080 @ 60 fps, 20.0 Mbps, V-Sync off, frame pacing on, 5.1 surround, overlay on",
                ClientTable.settingsText(new StreamSettings(1920, 1080, 60, 20_000, List.of(), null, false, true,
                        "5.1-surround", true)));
    }

    @Test
    void onlyAgentlessPairingsAreGreyedOut() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ClientTable table = new ClientTable(id -> null);
            ClientView agent = new ClientView("a", "tv", ClientView.Approval.APPROVED, true, ClientState.AVAILABLE,
                    null, StreamSettings.DEFAULT, Instant.now(), null, null, false, false, null, false, null, null,
                    false, false, null, null);
            table.setClients(List.of(agent, ClientView.agentless(new SunshineClient.PairedClient("phone", "u"), false),
                    agent), null);
            JTable t = table.component();
            for (int col = 0; col < t.getColumnCount(); col++) {
                // Rendering the greyed row first must not leave its colour on the shared renderer.
                assertEquals(UIManager.getColor("Label.disabledForeground"), foreground(t, 1, col));
                assertNotEquals(UIManager.getColor("Label.disabledForeground"), foreground(t, 2, col));
                assertEquals(t.getForeground(), foreground(t, 0, col));
            }
        });
    }

    private static java.awt.Color foreground(JTable t, int row, int col) {
        return t.prepareRenderer(t.getCellRenderer(row, col), row, col).getForeground();
    }

    @Test
    void onlyFaultsCountAsFaults() {
        Set<String> faults = Set.of("Sunshine not running", "Moonlight missing", "Error");
        List<ClientState> states = new ArrayList<>(List.of(ClientState.values()));
        states.add(null);
        Set<String> seen = new java.util.HashSet<>();
        for (int flags = 0; flags < 64; flags++) {
            for (ClientState state : states) {
                ClientView c = new ClientView("a", "tv",
                        (flags & 1) != 0 ? ClientView.Approval.APPROVED : ClientView.Approval.PENDING,
                        (flags & 2) != 0, state, null, StreamSettings.DEFAULT, null, null, null, (flags & 4) != 0,
                        (flags & 8) != 0, null, (flags & 16) != 0, null, null, false, (flags & 32) != 0, null, null);
                assertEquals(faults.contains(c.statusLabel()), c.statusIsFault(), c.statusLabel());
                seen.add(c.statusLabel());
            }
        }
        assertTrue(seen.containsAll(faults));
    }
}
