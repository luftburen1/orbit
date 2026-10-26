package orbit.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentConfigTest {

    @TempDir
    Path dir;

    @Test
    void firstLoadGeneratesStableIdAndDefaults() throws Exception {
        Path file = dir.resolve("agent.json");
        AgentConfig first = AgentConfig.load(file);
        assertNotNull(first.agentId());
        assertNull(first.moonlightCommand(), "unset means detect");
        assertEquals(first, AgentConfig.load(file));
    }

    @Test
    void unreadableFileWithoutCopyIsMovedAsideAndStartsAfresh() throws Exception {
        Path file = dir.resolve("agent.json");
        Files.writeString(file, "{\"agentId\":\"x\",");
        AgentConfig config = AgentConfig.load(file);
        assertNotNull(config.agentId());
        assertNull(config.token());
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(f -> f.getFileName().toString().startsWith("agent.json.broken-")));
        }
    }

    @Test
    void emptyFileAfterPowerLossFallsBackToTheCopy() throws Exception {
        Path file = dir.resolve("agent.json");
        AgentConfig saved = AgentConfig.load(file).withToken("secret");
        saved.save(file);
        Files.writeString(file, "");
        assertEquals(saved, AgentConfig.load(file));
    }

    @Test
    void oldDefaultCommandMeansDetect() throws Exception {
        Path file = dir.resolve("agent.json");
        Files.writeString(file, "{\"agentId\":\"x\",\"moonlightCommand\":[\"moonlight\"]}");
        assertNull(AgentConfig.load(file).moonlightCommand());
    }

    @Test
    void keepsUserSettings() throws Exception {
        Path file = dir.resolve("agent.json");
        Files.writeString(file, """
                {"agentId":"x","host":" 10.0.0.9 ","moonlightCommand":["flatpak","run","com.moonlight_stream.Moonlight"]}
                """);
        AgentConfig c = AgentConfig.load(file).withToken("t");
        c.save(file);
        AgentConfig reloaded = AgentConfig.load(file);
        assertEquals("x", reloaded.agentId());
        assertEquals("10.0.0.9", reloaded.host());
        assertEquals("t", reloaded.token());
        assertEquals(3, reloaded.moonlightCommand().size());
    }

    @Test
    void idleImageExpandsHome() throws Exception {
        Path file = dir.resolve("agent.json");
        Files.writeString(file, "{\"agentId\":\"x\",\"idleImage\":\"~/Pictures/tv.png\"}");
        assertEquals(Path.of(System.getProperty("user.home"), "Pictures", "tv.png"),
                AgentConfig.load(file).idleImagePath());
        Files.writeString(file, "{\"agentId\":\"x\",\"idleImage\":\" \"}");
        assertNull(AgentConfig.load(file).idleImagePath(), "blank means no image");
    }

    @Test
    void streamsAllowedAndTrayByDefaultAndKeptWhenTurnedOff() throws Exception {
        Path file = dir.resolve("agent.json");
        AgentConfig c = AgentConfig.load(file);
        assertTrue(c.streamsAllowed());
        assertFalse(c.idleScreenAtStartup(), "a desktop gets the tray unless told otherwise");

        c.withAllowStreams(false).save(file);
        assertFalse(AgentConfig.load(file).streamsAllowed());
        AgentConfig.load(file).withAllowStreams(true).save(file);
        assertNull(AgentConfig.load(file).allowStreams(), "allowed is the default, so it isn't written");

        Files.writeString(file, "{\"idleScreen\":\"fullscreen\"}");
        assertTrue(AgentConfig.load(file).idleScreenAtStartup());
    }
}
