// One-shot KWin script Orbit runs when a client's TV remote is about to send keys or move the mouse after a pause: if
// focus is off the monitor Sunshine streams, e.g. because someone at the host clicked elsewhere, gives it back to the
// topmost window there (the one last opened or used), or to the desktop there. With POINTER, the cursor goes onto it
// too if it's off that monitor. Orbit fills in the values below.
const OUTPUT = "@OUTPUT@";
const POINTER = @POINTER@;

function focusStream() {
    const screen = workspace.screens.find(s => s.name === OUTPUT);
    if (!screen) {
        return;
    }
    const focused = focusedOn(screen);
    const c = workspace.cursorPos;
    const g = screen.geometry;
    const cursorThere = c.x >= g.x && c.x < g.x + g.width && c.y >= g.y && c.y < g.y + g.height;
    if (focused && (!POINTER || cursorThere)) {
        return;
    }
    if (!focused) {
        // The desktop there counts only when it has no window: KWin may have given it focus (see pointerTo).
        const target = topWindowOn(screen) || workspace.windowList().find(w => w.desktopWindow && w.output === screen);
        if (!target) {
            return;
        }
        workspace.activeWindow = target;
    }
    if (POINTER && !cursorThere) {
        pointerTo(screen);
    }
}

focusStream();
