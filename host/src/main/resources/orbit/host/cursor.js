// Shared by Orbit's KWin scripts; Orbit puts it in front of each.

const SHORTCUT = ["org.kde.kglobalaccel", "/component/kwin", "org.kde.kglobalaccel.Component", "invokeShortcut"];

// The topmost normal window on the screen (the one last opened or used there), or null.
function topWindowOn(screen) {
    const stack = workspace.stackingOrder;
    for (let i = stack.length - 1; i >= 0; i--) {
        const w = stack[i];
        if (w.normalWindow && !w.minimized && w.output === screen) {
            return w;
        }
    }
    return null;
}

// Whether focus is on the screen: on a window there, or on its desktop when it has no window to focus.
function focusedOn(screen) {
    const a = workspace.activeWindow;
    return !!a && a.output === screen && (!a.desktopWindow || !topWindowOn(screen));
}

// Moves the cursor onto the screen: onto the focused window if it's a real one there, else to the screen's centre.
// KWin's "Move Mouse to Focus" skips desktops, and "Move Mouse to Center" centres on KWin's active screen, which
// follows the cursor; "Switch to Screen N" (N in KWin's screen order) makes this one active first. That also gives
// focus to the screen's last used window, or its desktop, so it's only used while focus is on the screen already.
function pointerTo(screen) {
    const a = workspace.activeWindow;
    if (a && !a.desktopWindow && a.output === screen) {
        callDBus(...SHORTCUT, "MoveMouseToFocus");
        return;
    }
    if (!a || a.output !== screen) {
        return;
    }
    const i = workspace.screenOrder.findIndex(s => s.name === screen.name);
    if (i >= 0 && i < 8) {
        callDBus(...SHORTCUT, "Switch to Screen " + i, () => callDBus(...SHORTCUT, "MoveMouseToCenter"));
    }
}

// Gives focus to the monitor named `output` and moves the cursor there, once: to the window already in focus there,
// else to its desktop. The monitor may have just been turned on, so it waits for its desktop if needed. Skipped once
// `skip()` says so, e.g. when the app's window got there first. Plasma puts its on-screen display on this monitor then.
function focusOutput(output, skip) {
    let done = false;
    function attempt() {
        if (done || skip()) {
            return;
        }
        const screen = workspace.screens.find(s => s.name === output);
        const a = workspace.activeWindow;
        if (screen && a && !a.desktopWindow && a.output === screen) {
            done = true;
            pointerTo(screen);
            return;
        }
        const desktop = screen && workspace.windowList().find(w => w.desktopWindow && w.output === screen);
        if (!desktop) {
            return;
        }
        done = true;
        workspace.activeWindow = desktop;
        pointerTo(screen);
    }
    workspace.windowAdded.connect(w => {
        if (w.desktopWindow) {
            // Its monitor may be set only after it's added.
            w.outputChanged.connect(attempt);
        }
        attempt();
    });
    workspace.screensChanged.connect(attempt);
    attempt();
}
