package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import orbit.protocol.Message;
import org.junit.jupiter.api.Test;

class PlasmaOsdTest {

    @Test
    void showsTheRemotesModesAndStop() {
        assertEquals("Mouse mode", PlasmaOsd.notice(Message.RemoteAction.MOUSE_MODE).orElseThrow().text());
        assertEquals("Key mode", PlasmaOsd.notice(Message.RemoteAction.KEY_MODE).orElseThrow().text());
        assertEquals("media-playback-stop", PlasmaOsd.notice(Message.RemoteAction.STOP).orElseThrow().icon());
    }

    @Test
    void ignoresActionsFromNewerAgents() {
        assertEquals(Optional.empty(), PlasmaOsd.notice("somethingNew"));
        assertEquals(Optional.empty(), PlasmaOsd.notice(null));
    }
}
