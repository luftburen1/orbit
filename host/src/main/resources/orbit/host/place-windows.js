// KWin script Orbit loads while a stream runs: asks Orbit about each window that opens or gets focus, and moves it to
// the monitor Sunshine streams (or makes it fullscreen there) when Orbit says so, and gives it focus and the cursor.
// Before any window comes, it gives focus to that monitor. A window it placed that leaves the monitor again (Wine sizes
// its games to the primary monitor) is reported as "left", at most RETURNS times, so an app that keeps pulling its
// window back doesn't fight Orbit forever.
// Orbit's "Starting…" splash (LaunchSplash) goes fullscreen on that monitor below every other window, unfocused, until
// Orbit closes it. Its notice asking to leave the mouse and keyboard alone (StartNotice) goes to the top of the main
// monitor, above every other window. Orbit fills in the four values below.
const ORBIT = "@ORBIT_SERVICE@";
const OUTPUT = "@OUTPUT@";
const SPLASH = "@SPLASH@";
const NOTICE = "@NOTICE@";
const RETURNS = 3;
// The windows placed so far, with how often each was reported as left.
const placed = new Map();

function target() {
    return workspace.screens.find(s => s.name === OUTPUT);
}

function isSplash(w) {
    return !!w && w.caption === SPLASH;
}

function isNotice(w) {
    return !!w && w.caption === NOTICE;
}

// Where whoever sits at the host looks: the first monitor in Plasma's order (the primary) that isn't Sunshine's.
function placeNotice(w) {
    const screen = workspace.screenOrder.find(s => s.name !== OUTPUT);
    if (!screen) {
        return;
    }
    w.skipTaskbar = true;
    w.skipSwitcher = true;
    w.skipPager = true;
    w.keepAbove = true;
    if (w.output !== screen) {
        workspace.sendClientToScreen(w, screen);
    }
    const g = screen.geometry;
    const f = w.frameGeometry;
    w.frameGeometry = {
        x: g.x + Math.max(0, (g.width - f.width) / 2),
        y: g.y + Math.round(g.height / 10),
        width: f.width,
        height: f.height
    };
}

// Behind the app's windows, out of the taskbar and the window switcher; Orbit made it unfocusable already.
function placeSplash(w) {
    const screen = target();
    if (!screen) {
        return;
    }
    w.skipTaskbar = true;
    w.skipSwitcher = true;
    w.skipPager = true;
    w.keepBelow = true;
    if (w.output !== screen) {
        workspace.sendClientToScreen(w, screen);
    }
    w.fullScreen = true;
    if (!w.fullScreen) {
        w.frameGeometry = screen.geometry;
    }
}

function consider(w, event) {
    const screen = target();
    if (!w || !w.normalWindow || !screen || isSplash(w) || isNotice(w)) {
        return;
    }
    const f = w.frameGeometry;
    const g = screen.geometry;
    const query = [event, w.pid, Math.round(f.width), Math.round(f.height), w.fullScreen, w.output === screen,
        Math.round(g.width), Math.round(g.height)].join(" ");
    callDBus(ORBIT, "/orbit/Windows", "orbit.Windows", "place", query, function (action) {
        try {
            if (action !== "move" && action !== "fullscreen") {
                return;
            }
            if (w.output !== screen) {
                workspace.sendClientToScreen(w, screen);
            }
            if (action === "fullscreen") {
                w.fullScreen = true;
            } else if (!w.fullScreen) {
                const size = w.frameGeometry;
                w.frameGeometry = {
                    x: g.x + Math.max(0, (g.width - size.width) / 2),
                    y: g.y + Math.max(0, (g.height - size.height) / 2),
                    width: size.width,
                    height: size.height
                };
            }
            watch(w);
            workspace.activeWindow = w;
            // The cursor too: it would stay on the host's own monitor, out of the stream's sight.
            pointerTo(screen);
        } catch (e) {
            // The window closed before Orbit answered.
        }
    });
}

function watch(w) {
    if (placed.has(w)) {
        return;
    }
    placed.set(w, 0);
    w.outputChanged.connect(() => {
        const left = placed.get(w);
        const screen = target();
        if (screen && w.output !== screen && left < RETURNS) {
            placed.set(w, left + 1);
            consider(w, "left");
        }
    });
    w.closed.connect(() => placed.delete(w));
}

workspace.windowAdded.connect(w => isSplash(w) ? placeSplash(w) : isNotice(w) ? placeNotice(w) : consider(w, "added"));
workspace.windowActivated.connect(w => consider(w, "activated"));
// Until the app's window comes: focus and the cursor on Sunshine's monitor, so Plasma shows Orbit's "Starting…" notice
// there, and so do apps that open on the monitor in focus.
focusOutput(OUTPUT, () => placed.size > 0);
