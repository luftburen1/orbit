package orbit.host;

import static orbit.host.ClientActivities.PAIRING;
import static orbit.host.ClientActivities.RECONNECTING;
import static orbit.host.ClientActivities.STARTING;
import static orbit.host.ClientActivities.STOPPING;
import static orbit.host.ClientActivities.UNPAIRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import orbit.protocol.ClientState;
import orbit.protocol.StreamSettings;
import org.junit.jupiter.api.Test;

class ClientActivitiesTest {

    private static ClientView client(boolean online, ClientState state) {
        return new ClientView("a", "den", ClientView.Approval.APPROVED, online, state, null, StreamSettings.DEFAULT,
                Instant.EPOCH, "6.1.0", "0.1.0", false, false, null, false, null, null, false, false, null, null);
    }

    @Test
    void aBusyClientTakesNoSecondActivity() {
        ClientActivities activities = new ClientActivities();
        assertTrue(activities.begin("a", STOPPING));
        assertFalse(activities.begin("a", STARTING));
        assertEquals(STOPPING, activities.get("a"));
        assertFalse(activities.end("a", STARTING));
        assertTrue(activities.end("a", STOPPING));
        assertFalse(activities.busy("a"));
    }

    @Test
    void stoppingEndsOnceTheClientNoLongerStreams() {
        ClientActivities activities = new ClientActivities();
        activities.begin("a", STOPPING);
        assertFalse(activities.settle(List.of(client(true, ClientState.STREAMING))));
        assertTrue(activities.settle(List.of(client(true, ClientState.AVAILABLE))));
        assertNull(activities.get("a"));
    }

    @Test
    void startingEndsOnceTheClientStreamsOrIsGone() {
        ClientActivities activities = new ClientActivities();
        activities.begin("a", STARTING);
        assertFalse(activities.settle(List.of(client(true, ClientState.AVAILABLE))));
        assertTrue(activities.settle(List.of(client(true, ClientState.STREAMING))));

        activities.begin("a", STARTING);
        assertTrue(activities.settle(List.of(client(false, ClientState.AVAILABLE))));
    }

    @Test
    void unpairingEndsOnceTheClientSaysItIsNotPaired() {
        ClientActivities activities = new ClientActivities();
        activities.begin("a", UNPAIRING);
        assertFalse(activities.settle(List.of(client(true, ClientState.AVAILABLE))));
        assertTrue(activities.settle(List.of(client(true, ClientState.UNPAIRED))));
    }

    @Test
    void pairingAndReconnectingWaitForTheirCommand() {
        ClientActivities activities = new ClientActivities();
        activities.begin("a", PAIRING);
        activities.begin("b", RECONNECTING);
        assertFalse(activities.settle(List.of()));
        assertTrue(activities.replace("b", RECONNECTING, STARTING));
        assertEquals(STARTING, activities.get("b"));
    }

    @Test
    void unpairingAPairingWithoutAnAgentEndsOnceSunshineNoLongerListsIt() {
        ClientView phone = ClientView.agentless(new SunshineClient.PairedClient("phone", "u-1"), false);
        ClientActivities activities = new ClientActivities();
        activities.begin(phone.agentId(), UNPAIRING);
        assertFalse(activities.settle(List.of(phone)), "never online, but still listed");
        assertTrue(activities.settle(List.of()));
    }
}
