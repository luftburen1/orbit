package orbit.host;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import orbit.protocol.JsonFiles;

/**
 * Turns the monitor Sunshine streams off while nothing streams, and on again before a stream. Only monitors Orbit
 * turned off are turned back on, and never the primary monitor or the last one that's on is turned off.
 */
final class MonitorPower {

    private static final Logger LOG = Logger.getLogger(MonitorPower.class.getName());

    private final DisplayControl display;
    /** How long a monitor gets after turning on before a stream starts, so the desktop has set it up. */
    private final Duration settle;
    /** Monitors Orbit turned off, to turn back on later. Saved, so a crash doesn't leave them off for good. */
    private final Set<String> turnedOff = new LinkedHashSet<>();
    /** Where {@link #turnedOff} is saved, or null to keep it in memory only. */
    private Path file;

    /** What's saved in {@code monitor-power.json}. */
    record Store(List<String> turnedOff) {
    }

    MonitorPower(DisplayControl display, Duration settle) {
        this.display = display;
        this.settle = settle;
    }

    /**
     * Keeps the monitors Orbit turned off in {@code file}, and reads what an earlier run left there: if Orbit was
     * killed before it could turn them back on, the next run still knows to.
     */
    synchronized void rememberIn(Path file) {
        this.file = file;
        try {
            JsonFiles.read(file, Store.class).ifPresent(s -> {
                if (s.turnedOff() != null) {
                    turnedOff.addAll(s.turnedOff());
                }
            });
        } catch (IOException e) {
            LOG.log(Level.WARNING, "cannot read " + file, e);
        }
        if (!turnedOff.isEmpty()) {
            LOG.info(() -> "an earlier run turned off " + String.join(", ", turnedOff));
        }
    }

    /**
     * Why {@code output} can't be turned off while idle, or empty if it can.
     *
     * @param outputs the connected monitors
     */
    static Optional<String> refusal(String output, List<DisplayControl.Output> outputs) {
        Optional<DisplayControl.Output> monitor = outputs.stream().filter(o -> o.name().equals(output)).findFirst();
        if (monitor.isEmpty()) {
            return Optional.of(output + " isn't connected");
        }
        if (monitor.get().primary()) {
            return Optional.of(output + " is the primary monitor");
        }
        boolean otherOn = outputs.stream().anyMatch(o -> !o.name().equals(output) && o.enabled());
        if (!otherOn) {
            return Optional.of(output + " is the only monitor that's on");
        }
        return Optional.empty();
    }

    Optional<String> refusal(String output) throws IOException, InterruptedException {
        return refusal(output, display.outputs());
    }

    /**
     * Turns {@code output} on if it's off, and waits until the desktop reports it on and it has settled.
     *
     * @return true if it had to be turned on
     */
    synchronized boolean ensureOn(String output) throws IOException, InterruptedException {
        if (isOn(output)) {
            return false;
        }
        display.setEnabled(output, true);
        forget(output);
        for (int i = 0; i < 50 && !isOn(output); i++) {
            Thread.sleep(100);
        }
        if (!isOn(output)) {
            throw new IOException(output + " did not turn on");
        }
        LOG.info(() -> output + " turned on for streaming");
        Thread.sleep(settle.toMillis());
        return true;
    }

    /**
     * Sets the monitors as they should be while nothing streams: {@code output} off if {@code turnOff} (and it may
     * be), and any other monitor Orbit turned off back on.
     *
     * @param output Sunshine's monitor, or null if unknown
     */
    synchronized void idle(String output, boolean turnOff) {
        if (!turnOff && turnedOff.isEmpty()) {
            return;
        }
        try {
            for (String other : List.copyOf(turnedOff)) {
                if (!turnOff || !other.equals(output)) {
                    turnOn(other);
                }
            }
            if (turnOff && output != null && !turnedOff.contains(output)) {
                List<DisplayControl.Output> outputs = display.outputs();
                boolean on = outputs.stream().anyMatch(o -> o.name().equals(output) && o.enabled());
                Optional<String> refusal = refusal(output, outputs);
                if (on && refusal.isEmpty()) {
                    // Saved before turning it off: if Orbit dies in between, the next run turns on a monitor that's
                    // already on, which is harmless; the other way round it would stay off for good.
                    turnedOff.add(output);
                    save();
                    try {
                        display.setEnabled(output, false);
                    } catch (IOException e) {
                        forget(output);
                        throw e;
                    }
                    LOG.info(() -> output + " turned off until the next stream");
                } else if (on) {
                    LOG.info(() -> "not turning " + output + " off: " + refusal.get());
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "cannot change which monitors are on", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Turns back on every monitor Orbit turned off; when Orbit quits, so Sunshine works without it. */
    synchronized void restoreAll() {
        for (String output : List.copyOf(turnedOff)) {
            try {
                turnOn(output);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "cannot turn " + output + " back on", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    synchronized boolean anyTurnedOff() {
        return !turnedOff.isEmpty();
    }

    private void turnOn(String output) throws IOException, InterruptedException {
        if (display.outputs().stream().noneMatch(o -> o.name().equals(output))) {
            // Unplugged since: nothing to turn on, and it shouldn't fail every check from now on.
            forget(output);
            LOG.info(() -> output + " isn't connected any more; no longer turning it back on");
            return;
        }
        display.setEnabled(output, true);
        forget(output);
        LOG.info(() -> output + " turned back on");
    }

    private void forget(String output) {
        if (turnedOff.remove(output)) {
            save();
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            JsonFiles.write(file, new Store(List.copyOf(turnedOff)));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "cannot save " + file, e);
        }
    }

    private boolean isOn(String output) throws IOException, InterruptedException {
        return display.outputs().stream().anyMatch(o -> o.name().equals(output) && o.enabled());
    }
}
