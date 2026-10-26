package orbit.host;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Reads and changes the host's monitor modes. */
interface DisplayControl {

    /** A monitor mode; {@code id} is what {@link #setMode} takes. */
    record Mode(String id, int width, int height, double refreshHz) {
        @Override
        public String toString() {
            return width + "×" + height + " @ " + Math.round(refreshHz) + " Hz";
        }
    }

    /**
     * A connected monitor, named like Sunshine's KMS ids (e.g. {@code DP-2}).
     *
     * @param primary the desktop's main monitor, which Orbit never turns off
     */
    record Output(String name, boolean enabled, boolean primary, String currentModeId, List<Mode> modes) {
        Optional<Mode> currentMode() {
            return modes.stream().filter(m -> m.id().equals(currentModeId)).findFirst();
        }
    }

    List<Output> outputs() throws IOException, InterruptedException;

    void setMode(String output, String modeId) throws IOException, InterruptedException;

    /** Turns a monitor on or off. */
    void setEnabled(String output, boolean enabled) throws IOException, InterruptedException;

    /** Where a monitor that's on sits on the desktop, in its logical (scaled) coordinates; empty if unknown. */
    default Optional<Rectangle> bounds(String output) throws IOException, InterruptedException {
        return Optional.empty();
    }
}
