package orbit.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import orbit.protocol.JsonFiles;

/**
 * Agent settings, stored in {@code agent.json} in the Orbit config directory.
 *
 * @param agentId          stable identity of this client, generated on first start
 * @param token            issued by the host when it approves this client
 * @param host             optional {@code address[:port]} of the Orbit host; when set, mDNS discovery is skipped
 * @param hostName         optional mDNS instance name to pick when several Orbit hosts are on the network
 * @param moonlightCommand how to run Moonlight, e.g. {@code ["moonlight-qt"]} or
 *                         {@code ["flatpak", "run", "com.moonlight_stream.Moonlight"]}; null to detect it
 * @param idleImage        optional image (PNG, JPEG, GIF or BMP) shown fullscreen while not streaming; a leading
 *                         {@code ~/} means the home directory
 * @param animateIdleScreen false to keep the idle screen's logo still, e.g. on slow hardware; animated if absent
 * @param idleScreen       on a desktop: "fullscreen" to show the idle screen at startup (e.g. a desktop box by the
 *                         TV), else "tray" (the default) to start with just the tray icon. The tray can show it any
 *                         time, and switches this. A text console always shows the idle screen
 * @param allowStreams     false while someone at this client turned streams off in its tray; allowed if absent
 * @param hdmiCec          "auto" (the default) to turn the TV on and switch it to this client's input over HDMI-CEC
 *                         before a stream, where a {@code /dev/cec*} device exists; "off" not to;
 *                         or a device, e.g. {@code /dev/cec1}, to use that HDMI port
 */
public record AgentConfig(String agentId, String token, String host, String hostName, List<String> moonlightCommand,
                          String idleImage, Boolean animateIdleScreen, String idleScreen, Boolean allowStreams,
                          String hdmiCec) {

    public static final String FILE_NAME = "agent.json";
    private static final Logger LOG = Logger.getLogger(AgentConfig.class.getName());

    /**
     * Reads the config, completing what's missing. A file that can't be read, with no usable copy either, is moved
     * aside and the agent starts afresh (it then has to be approved again): an unattended client must not fail at
     * every start.
     */
    public static AgentConfig load(Path file) throws IOException {
        AgentConfig config;
        try {
            config = JsonFiles.read(file, AgentConfig.class).orElse(null);
        } catch (IOException e) {
            Path broken = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
            if (Files.exists(file)) {
                Files.move(file, broken);
            }
            // Unusable too, or it would have been read.
            Files.deleteIfExists(JsonFiles.backupOf(file));
            LOG.log(Level.SEVERE, "cannot read " + file + "; moved it to " + broken + " and starting with a new"
                    + " config (approve this client in Orbit again)", e);
            config = null;
        }
        if (config == null) {
            config = new AgentConfig(null, null, null, null, null, null, null, null, null, null);
        }
        AgentConfig completed = new AgentConfig(
                config.agentId != null ? config.agentId : UUID.randomUUID().toString(),
                config.token, blankToNull(config.host), blankToNull(config.hostName),
                normalizeCommand(config.moonlightCommand), blankToNull(config.idleImage), config.animateIdleScreen,
                blankToNull(config.idleScreen), config.allowStreams, blankToNull(config.hdmiCec));
        if (!completed.equals(config)) {
            completed.save(file);
        }
        return completed;
    }

    public void save(Path file) throws IOException {
        JsonFiles.write(file, this);
    }

    public AgentConfig withToken(String token) {
        return new AgentConfig(agentId, token, host, hostName, moonlightCommand, idleImage, animateIdleScreen,
                idleScreen, allowStreams, hdmiCec);
    }

    /** "fullscreen" to show the idle screen at startup, else "tray". */
    public AgentConfig withIdleScreenAtStartup(boolean show) {
        return new AgentConfig(agentId, token, host, hostName, moonlightCommand, idleImage, animateIdleScreen,
                show ? "fullscreen" : null, allowStreams, hdmiCec);
    }

    public AgentConfig withAllowStreams(boolean allow) {
        return new AgentConfig(agentId, token, host, hostName, moonlightCommand, idleImage, animateIdleScreen,
                idleScreen, allow ? null : false, hdmiCec);
    }

    /** Whether the host may start streams here: yes unless someone turned them off in the tray. */
    public boolean streamsAllowed() {
        return !Boolean.FALSE.equals(allowStreams);
    }

    /** On a desktop, whether the idle screen opens at startup; the tray icon is there either way. */
    public boolean idleScreenAtStartup() {
        return "fullscreen".equalsIgnoreCase(idleScreen);
    }

    /** Whether to animate the idle screen's logo: yes unless {@code animateIdleScreen} is false. */
    public boolean animateIdle() {
        return !Boolean.FALSE.equals(animateIdleScreen);
    }

    /** {@link #idleImage} as a path, with {@code ~/} expanded; null if none is set. */
    public Path idleImagePath() {
        if (idleImage == null) {
            return null;
        }
        return idleImage.startsWith("~/")
                ? Path.of(System.getProperty("user.home"), idleImage.substring(2))
                : Path.of(idleImage);
    }

    /**
     * Null when Moonlight should be detected. Earlier versions wrote the default {@code ["moonlight"]} into every
     * config, so that value also means "detect" (detection tries {@code moonlight} first anyway).
     */
    private static List<String> normalizeCommand(List<String> command) {
        if (command == null || command.isEmpty() || command.equals(List.of("moonlight"))) {
            return null;
        }
        return List.copyOf(command);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
