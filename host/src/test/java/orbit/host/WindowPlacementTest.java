package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import orbit.host.WindowPlacement.Action;
import orbit.host.WindowPlacement.Event;
import orbit.host.WindowPlacement.Window;
import org.junit.jupiter.api.Test;

class WindowPlacementTest {

    private static final long ORBIT = 900;

    /** The host's processes as seen while Valheim ran from Steam Big Picture. */
    private final Map<Long, Long> parents = new HashMap<>();
    private final Map<Long, String> names = new HashMap<>();
    private final AtomicLong now = new AtomicLong(1_000_000_000L);

    private final WindowPlacement.Processes processes = new WindowPlacement.Processes() {
        @Override
        public OptionalLong parent(long pid) {
            Long p = parents.get(pid);
            return p == null ? OptionalLong.empty() : OptionalLong.of(p);
        }

        @Override
        public Optional<String> name(long pid) {
            return Optional.ofNullable(names.get(pid));
        }
    };

    WindowPlacementTest() {
        process(1, 0, "systemd");
        process(2755, 1, "systemd");
        process(16959, 2755, "steam.sh");
        process(17065, 16959, "steam");
        process(17313, 17065, "steamwebhelper");
        process(38228, 17065, "reaper");
        process(38295, 38228, "pv-adverb");
        process(38327, 38295, "valheim.x86_64");
        process(4064, 2755, "sunshine");
        process(5000, 4064, "kodi");
        process(6000, 2755, "firefox");
        process(3000, 2755, "plasmashell");
        process(3100, 3000, "dolphin");
        process(ORBIT, 2755, "java");
    }

    private void process(long pid, long parent, String name) {
        parents.put(pid, parent);
        names.put(pid, name);
    }

    private WindowPlacement placement() {
        return new WindowPlacement(processes, KdePlasmaDesktop.SHELL_PROCESSES, ORBIT, now::get);
    }

    private static Window added(long pid, int width, int height) {
        return new Window(Event.ADDED, pid, width, height, false, false, 1920, 1080);
    }

    private static Window activated(long pid, int width, int height) {
        return new Window(Event.ACTIVATED, pid, width, height, false, false, 1920, 1080);
    }

    @Test
    void bigPictureThenItsGamesGoToSunshinesMonitor() {
        WindowPlacement p = placement();
        // Steam was running; Big Picture opens (fullscreen) on the desk monitor.
        assertEquals(Action.FULLSCREEN, p.decide(new Window(Event.ADDED, 17313, 3440, 1440, true, false, 1920, 1080)));
        // Valheim: 1920×1080 with its title bar, so it fills the TV and is made fullscreen.
        assertEquals(Action.FULLSCREEN, p.decide(added(38327, 1920, 1080)));
        // A game's small launcher or dialog is only moved.
        assertEquals(Action.MOVE, p.decide(added(38295, 640, 480)));
    }

    @Test
    void aWindowThatLeavesIsPutBack() {
        // Zwift in netbrain/zwift's container: Wine sizes the game window to the desk monitor's work area after Orbit
        // moved it, so it comes back the size of that monitor, bigger than the TV.
        process(23435, 1, "containerd-shim");
        process(23457, 23435, "bash");
        process(20642, 23457, "conhost.exe");
        process(23750, 23457, "main");
        WindowPlacement p = placement();
        assertEquals(Action.MOVE, p.decide(added(20642, 640, 480)));
        assertEquals(Action.MOVE, p.decide(added(23750, 1024, 768)));
        assertEquals(Action.FULLSCREEN, p.decide(new Window(Event.LEFT, 23750, 3432, 1406, false, false, 1920, 1080)));
        assertEquals(Action.MOVE, p.decide(new Window(Event.LEFT, 23750, 800, 600, false, false, 1920, 1080)));
        assertEquals(Action.IGNORE, p.decide(new Window(Event.LEFT, 6000, 800, 600, false, false, 1920, 1080)),
                "not the app's");
    }

    @Test
    void otherAppsStayWhereTheyAre() {
        WindowPlacement p = placement();
        p.decide(added(17313, 3440, 1440));
        assertEquals(Action.IGNORE, p.decide(added(6000, 1920, 1080)), "Firefox at the desk");
        assertEquals(Action.IGNORE, p.decide(added(3100, 800, 600)), "Dolphin from the menu");
    }

    @Test
    void onlyNewWindowsOfTheFamilyAfterTheFirst() {
        WindowPlacement p = placement();
        p.decide(added(17313, 3440, 1440));
        assertEquals(Action.IGNORE, p.decide(activated(17313, 1920, 1080)), "focus moving around isn't news");
    }

    @Test
    void handedOffAppIsFoundWhenItsWindowGetsFocus() {
        WindowPlacement p = placement();
        // Steam brings an existing window to the front instead of opening one.
        assertEquals(Action.FULLSCREEN, p.decide(activated(17313, 3440, 1440)));
        assertEquals(Action.FULLSCREEN, p.decide(added(38327, 1920, 1080)));
    }

    @Test
    void appsSunshineStartsAreFollowedThroughSunshine() {
        WindowPlacement p = placement();
        assertEquals(Action.FULLSCREEN, p.decide(added(5000, 1920, 1080)));
        process(5100, 5000, "retroarch");
        assertEquals(Action.FULLSCREEN, p.decide(added(5100, 1920, 1080)), "a game started from Kodi");
        assertEquals(4064, p.familyOf(5000));
    }

    @Test
    void ownWindowAndTheDesktopAreNeverTheApp() {
        WindowPlacement p = placement();
        assertEquals(Action.IGNORE, p.decide(activated(ORBIT, 800, 600)), "Start was just clicked in Orbit");
        assertEquals(Action.IGNORE, p.decide(activated(3000, 1920, 1080)), "plasmashell");
        // A window started from the desktop's menu is its own family, not all of plasmashell's.
        assertEquals(3100, p.familyOf(3100));
        assertEquals(Action.MOVE, p.decide(activated(3100, 800, 600)));
        assertEquals(Action.IGNORE, p.decide(added(6000, 800, 600)));
    }

    @Test
    void aFirstWindowTooLateIsSomebodyElses() {
        WindowPlacement p = placement();
        now.addAndGet(WindowPlacement.FIRST_WINDOW_WITHIN.toNanos() + 1);
        assertEquals(Action.IGNORE, p.decide(added(17313, 1920, 1080)));
    }

    @Test
    void familyIsTheTopProcessBelowTheServiceManager() {
        WindowPlacement p = placement();
        assertEquals(16959, p.familyOf(38327), "Valheim belongs to steam.sh");
        assertEquals(16959, p.familyOf(17313));
        assertEquals(6000, p.familyOf(6000));
        assertEquals(7000, p.familyOf(7000), "an unknown process is its own family");
    }

    @Test
    void parsesWhatTheScriptSends() {
        assertEquals(new Window(Event.ADDED, 38327, 1920, 1080, false, false, 1920, 1080),
                Window.parse("added 38327 1920 1080 false false 1920 1080"));
        assertEquals(new Window(Event.ACTIVATED, 17313, 3440, 1440, true, true, 1920, 1080),
                Window.parse("activated 17313 3440 1440 true true 1920 1080\n"));
        assertEquals(new Window(Event.LEFT, 23750, 3432, 1406, false, false, 1920, 1080),
                Window.parse("left 23750 3432 1406 false false 1920 1080"));
        assertThrows(IllegalArgumentException.class, () -> Window.parse("added 1 2"));
    }

    @Test
    void scriptGetsTheServiceAndMonitor() {
        String script = KWinWindows.script("const A = \"@ORBIT_SERVICE@\"; const B = \"@OUTPUT@\";", ":1.42",
                "DP-\"2\"");
        assertEquals("const A = \":1.42\"; const B = \"DP-\\\"2\\\"\";", script);
        assertEquals("const S = \"" + LaunchSplash.TITLE + "\";",
                KWinWindows.script("const S = \"@SPLASH@\";", ":1.42", "DP-1"), "the splash's title");
        assertEquals("const N = \"" + StartNotice.TITLE + "\";",
                KWinWindows.script("const N = \"@NOTICE@\";", ":1.42", "DP-1"), "the notice's title");
        assertTrue(KWinWindows.class.getResource("place-windows.js") != null, "the script is in the jar");
    }
}
