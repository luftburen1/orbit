package orbit.agent;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** The agent's version, from the build; shown on the idle screen and sent to the host. */
final class AgentInfo {

    static final String VERSION = loadVersion();

    private AgentInfo() {
    }

    private static String loadVersion() {
        Properties p = new Properties();
        try (InputStream in = AgentInfo.class.getResourceAsStream("orbit-agent.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            // fall through to "unknown"
        }
        return p.getProperty("version", "unknown");
    }
}
