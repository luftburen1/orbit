package orbit.host;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import orbit.protocol.JsonFiles;

/**
 * Remembers the monitors Sunshine has listed. Sunshine lists only the monitors that are on when it starts, so after
 * it restarts while Orbit has its monitor turned off, that monitor would be missing from the Monitor menu.
 */
final class MonitorMemory {

    private static final Logger LOG = Logger.getLogger(MonitorMemory.class.getName());

    /** What's saved in {@code monitors.json}. */
    record Store(List<SunshineClient.Monitor> monitors) {
    }

    private final Path file;
    private final Map<String, SunshineClient.Monitor> known = new LinkedHashMap<>();

    /** @param file where the list is kept between runs, or null to keep it in memory only */
    MonitorMemory(Path file) {
        this.file = file;
        if (file != null) {
            try {
                JsonFiles.read(file, Store.class).ifPresent(s -> {
                    if (s.monitors() != null) {
                        s.monitors().forEach(m -> known.put(m.id(), m));
                    }
                });
            } catch (IOException e) {
                LOG.log(Level.WARNING, "cannot read " + file + "; starting without remembered monitors", e);
            }
        }
    }

    /**
     * Sunshine's list, completed with the monitors it left out: ones that were off when it started, or any its log
     * didn't list (e.g. a Sunshine version that words its log differently). Remembered ones keep Sunshine's
     * description; others are named by KDE. Extra monitors are only added once Sunshine has listed monitors at
     * some point, since KDE's connector names match Sunshine's only when it captures with KMS.
     *
     * @param fromSunshine what Sunshine lists now; remembered for next time
     * @param connected    the connected monitors by connector name, and whether each is on
     */
    synchronized List<SunshineClient.Monitor> complete(List<SunshineClient.Monitor> fromSunshine,
            Map<String, Boolean> connected) {
        boolean changed = false;
        for (SunshineClient.Monitor m : fromSunshine) {
            changed |= !m.equals(known.put(m.id(), m));
        }
        if (changed) {
            save();
        }
        List<SunshineClient.Monitor> result = new ArrayList<>(fromSunshine);
        if (known.isEmpty()) {
            return result;
        }
        for (String id : connected.keySet().stream().sorted().toList()) {
            if (result.stream().anyMatch(m -> m.id().equals(id))) {
                continue;
            }
            boolean on = connected.get(id);
            SunshineClient.Monitor remembered = known.get(id);
            String description = remembered != null ? remembered.description() + (on ? "" : " (off)")
                    : on ? "connected" : "off";
            result.add(new SunshineClient.Monitor(id, remembered != null ? remembered.index() : -1, description));
        }
        return result;
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            JsonFiles.write(file, new Store(List.copyOf(known.values())));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "cannot save " + file, e);
        }
    }
}
