package orbit.host;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The app's full name, version and author, for the About box and window title. "Orbit" stays the short name where
 * space is tight (tray, notifications, the agent). The version comes from the build.
 */
final class AppInfo {

    static final String NAME = "Orbit";
    static final String TAGLINE = "Orchestrate your Moonlight clients from the Sunshine host";
    static final String AUTHOR = "luftburen";
    static final String VERSION = loadVersion();

    private AppInfo() {
    }

    private static String loadVersion() {
        Properties p = new Properties();
        try (InputStream in = AppInfo.class.getResourceAsStream("orbit.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            // fall through to "unknown"
        }
        return p.getProperty("version", "unknown");
    }
}
