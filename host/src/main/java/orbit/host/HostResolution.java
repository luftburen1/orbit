package orbit.host;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import orbit.protocol.StreamSettings;

/**
 * Sets the monitor Sunshine captures to a stream's resolution and puts the original mode back afterwards. Only one
 * monitor is changed at a time, since Sunshine streams one monitor.
 */
final class HostResolution {

    private static final Logger LOG = Logger.getLogger(HostResolution.class.getName());

    /** The mode a stream got, and whether it is exactly what was asked for. */
    record Choice(DisplayControl.Mode mode, boolean exact) {
    }

    private final DisplayControl display;
    /** Monitor currently changed by Orbit and its original mode, or null. */
    private String changedOutput;
    private String originalModeId;

    HostResolution(DisplayControl display) {
        this.display = display;
    }

    DisplayControl display() {
        return display;
    }

    /**
     * Sets {@code output} to the mode closest to the stream settings and returns a note for the user, e.g.
     * "DP-2 set to 2560×1440 @ 120 Hz".
     */
    synchronized String apply(String output, StreamSettings settings) throws IOException, InterruptedException {
        if (changedOutput != null && !changedOutput.equals(output)) {
            restore();
        }
        DisplayControl.Output monitor = display.outputs().stream()
                .filter(o -> o.name().equals(output) && o.enabled())
                .findFirst()
                .orElseThrow(() -> new IOException("monitor " + output + " is not connected or enabled"));
        Choice choice = choose(monitor.modes(), settings.width(), settings.height(), settings.fps())
                .orElseThrow(() -> new IOException(output + " reports no modes"));
        if (changedOutput == null) {
            changedOutput = output;
            originalModeId = monitor.currentModeId();
        }
        if (!choice.mode().id().equals(monitor.currentModeId())) {
            display.setMode(output, choice.mode().id());
            LOG.info(() -> output + " set to " + choice.mode());
        }
        return output + " set to " + choice.mode() + (choice.exact() ? ""
                : " (closest it supports to " + settings.width() + "×" + settings.height() + ")");
    }

    /** Puts the changed monitor back to its original mode, if Orbit changed one. */
    synchronized void restore() {
        if (changedOutput == null) {
            return;
        }
        String output = changedOutput;
        String mode = originalModeId;
        changedOutput = null;
        originalModeId = null;
        try {
            display.setMode(output, mode);
            LOG.info(() -> output + " restored to its original mode");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "cannot restore the mode of " + output, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    synchronized boolean changed() {
        return changedOutput != null;
    }

    /**
     * The best mode for a stream: the exact size if the monitor has it, else the closest size with the same aspect
     * ratio, else the closest size. Among modes of that size, the refresh rate closest to the stream's FPS wins,
     * preferring the higher one on a tie.
     */
    static Optional<Choice> choose(List<DisplayControl.Mode> modes, int width, int height, int fps) {
        long pixels = (long) width * height;
        Comparator<DisplayControl.Mode> bySize =
                Comparator.comparingLong(m -> Math.abs((long) m.width() * m.height() - pixels));
        Optional<DisplayControl.Mode> size = modes.stream()
                .filter(m -> m.width() == width && m.height() == height).findFirst()
                .or(() -> modes.stream().filter(m -> sameAspect(m, width, height)).min(bySize))
                .or(() -> modes.stream().min(bySize));
        return size.map(s -> {
            DisplayControl.Mode best = modes.stream()
                    .filter(m -> m.width() == s.width() && m.height() == s.height())
                    .min(Comparator.<DisplayControl.Mode>comparingDouble(m -> Math.abs(m.refreshHz() - fps))
                            .thenComparing(Comparator.comparingDouble(DisplayControl.Mode::refreshHz).reversed()))
                    .orElse(s);
            return new Choice(best, best.width() == width && best.height() == height);
        });
    }

    private static boolean sameAspect(DisplayControl.Mode m, int width, int height) {
        return Math.abs((double) m.width() / m.height() - (double) width / height) < 0.01;
    }
}
