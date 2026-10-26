// KWin script Orbit loads while a stream of the desktop itself runs: gives focus to the desktop on the monitor Sunshine
// streams and moves the cursor there, once, so the stream's keys and mouse start out where the stream can see them.
// Orbit fills in the value below.
const OUTPUT = "@OUTPUT@";

focusOutput(OUTPUT, () => false);
