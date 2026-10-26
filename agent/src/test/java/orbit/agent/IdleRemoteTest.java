package orbit.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import orbit.protocol.Message;
import org.junit.jupiter.api.Test;

class IdleRemoteTest {

    private final List<Message.FromAgent> sent = new ArrayList<>();
    private long now = 1_000;
    private String refusal;
    private boolean takesControl = true;
    private final IdleRemote remote = new IdleRemote(new IdleRemote.Agent() {
        @Override
        public String requestRefusal() {
            return refusal;
        }

        @Override
        public boolean hostTakesControl() {
            return takesControl;
        }

        @Override
        public void send(Message.FromAgent message) {
            sent.add(message);
        }

        @Override
        public void updateIdleScreen() {
        }
    }, () -> now);

    private void openMenu() {
        remote.key(RemoteKeys.Key.PLAY_PAUSE);
        remote.appList(new Message.AppList(List.of("Desktop", "Steam Big Picture", "Kodi"), "Steam Big Picture",
                null), false);
    }

    @Test
    void playAsksForTheAppsAndOkStartsTheChosenOne() {
        remote.key(RemoteKeys.Key.PLAY_PAUSE);
        assertEquals(List.of(new Message.AppListRequest()), sent);
        assertEquals("Loading the apps…", remote.notice());

        remote.appList(new Message.AppList(List.of("Desktop", "Steam Big Picture", "Kodi"), "Steam Big Picture",
                null), false);
        assertNull(remote.notice());
        assertEquals("Steam Big Picture", remote.menu().app(), "opens on the last app");

        remote.key(RemoteKeys.Key.DOWN);
        remote.key(RemoteKeys.Key.OK);
        assertEquals(new Message.StreamRequest("Kodi"), sent.getLast());
        assertNull(remote.menu());
        assertEquals("Starting Kodi…", remote.notice());
    }

    @Test
    void backClosesTheMenuWithoutAStream() {
        openMenu();
        remote.key(RemoteKeys.Key.BACK);
        assertNull(remote.menu());
        assertEquals(1, sent.size(), "only the app list request");
    }

    @Test
    void anOlderHostGetsAnAppLessRequestAndArrowsDoNothing() {
        takesControl = false;
        remote.key(RemoteKeys.Key.UP);
        assertTrue(sent.isEmpty());
        remote.key(RemoteKeys.Key.OK);
        assertEquals(List.of(new Message.StreamRequest(null)), sent);
        assertEquals("Starting a stream…", remote.notice());
    }

    @Test
    void aRefusalShowsInsteadOfAsking() {
        refusal = "Not approved in Orbit yet";
        remote.key(RemoteKeys.Key.PLAY_PAUSE);
        assertTrue(sent.isEmpty());
        assertEquals("Not approved in Orbit yet", remote.notice());
    }

    @Test
    void playIsIgnoredRightAfterARequestUntilTheHostRefuses() {
        openMenu();
        remote.key(RemoteKeys.Key.OK);
        sent.clear();
        remote.key(RemoteKeys.Key.PLAY_PAUSE);
        assertTrue(sent.isEmpty(), "the host may still be starting it");

        remote.requestRefused("Sunshine isn't running");
        assertEquals("Couldn't start a stream: Sunshine isn't running", remote.notice());
        remote.key(RemoteKeys.Key.PLAY_PAUSE);
        assertEquals(List.of(new Message.AppListRequest()), sent);
    }

    @Test
    void theMenuAndNoticesTimeOut() {
        openMenu();
        now += IdleRemote.MENU_TIMEOUT.toNanos() - 1;
        remote.expire(false);
        assertEquals("Steam Big Picture", remote.menu().app());
        now += 1;
        remote.expire(false);
        assertNull(remote.menu());

        remote.showNotice("Hello");
        now += IdleRemote.NOTICE_TIME.toNanos();
        remote.expire(false);
        assertNull(remote.notice());
    }

    @Test
    void aStreamClosesTheMenuAndALateAppListIsDropped() {
        remote.key(RemoteKeys.Key.PLAY_PAUSE);
        remote.appList(new Message.AppList(List.of("Desktop"), null, null), true);
        assertNull(remote.menu(), "a stream overtook the request");

        openMenu();
        remote.expire(true);
        assertNull(remote.menu());
        assertNull(remote.notice());
    }

    @Test
    void aNewConnectionForgetsTheRequest() {
        remote.key(RemoteKeys.Key.PLAY_PAUSE);
        remote.reset();
        remote.appList(new Message.AppList(List.of("Desktop"), null, null), false);
        assertNull(remote.menu());
    }

    @Test
    void noAppsShowsWhy() {
        remote.key(RemoteKeys.Key.PLAY_PAUSE);
        remote.appList(new Message.AppList(List.of(), null, "Sunshine login missing"), false);
        assertNull(remote.menu());
        assertEquals("Couldn't load the apps: Sunshine login missing", remote.notice());
    }
}
