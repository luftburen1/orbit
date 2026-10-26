package orbit.host;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import orbit.protocol.JsonFiles;

/**
 * App names by Sunshine's id, learned from what Sunshine runs right after Orbit started an app: Sunshine's web API
 * doesn't give the ids, and they change with an app's name or cover. Saved in {@code app-ids.json} once
 * {@link #rememberIn} is called, so an app Orbit didn't start this run can still be joined. Thread-safe.
 */
final class AppIds {

    private static final Logger LOG = Logger.getLogger(AppIds.class.getName());

    /** What's saved in {@code app-ids.json}: app names by Sunshine's id, in text since JSON keys are. */
    record Store(Map<String, String> apps) {
    }

    private final Map<Long, String> apps = new ConcurrentHashMap<>();
    /** Where {@link #apps} is saved, or null to keep it in memory only. */
    private volatile Path file;

    /** Keeps the ids in {@code file} from now on, and reads what earlier runs learned. */
    void rememberIn(Path file) {
        this.file = file;
        try {
            JsonFiles.read(file, Store.class).map(Store::apps).ifPresent(saved -> saved.forEach((id, app) -> {
                try {
                    apps.put(Long.parseLong(id), app);
                } catch (NumberFormatException e) {
                    LOG.fine(() -> "ignoring app id " + id + " in " + file);
                }
            }));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "cannot read " + file, e);
        }
    }

    /** The app known by Sunshine's {@code id}, or null. */
    String name(long id) {
        return apps.get(id);
    }

    /** Remembers {@code id} (what Sunshine runs now that Orbit started {@code app}; ignored unless positive). */
    void learn(long id, String app) {
        if (id <= 0 || app.equals(apps.get(id))) {
            return;
        }
        // An app gets a new id when its name or cover changes; the old one is no use any more.
        apps.values().removeIf(app::equals);
        apps.put(id, app);
        LOG.fine(() -> "Sunshine's id for " + app + " is " + id);
        save();
    }

    private void save() {
        Path target = file;
        if (target == null) {
            return;
        }
        Map<String, String> saved = new TreeMap<>();
        apps.forEach((id, app) -> saved.put(Long.toString(id), app));
        try {
            JsonFiles.write(target, new Store(saved));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "cannot save " + target, e);
        }
    }
}
