package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import orbit.protocol.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostConfigTest {

    @TempDir
    Path dir;

    @Test
    void readsTheFlatFileOfAnOlderOrbitAndWritesSections() throws Exception {
        Path file = dir.resolve(HostConfig.FILE_NAME);
        Files.writeString(file, """
                {"port":47900,"sunshineUrl":"https://localhost:47990","sunshineUser":"me","sunshinePassword":"pw",
                 "turnOffStreamingMonitor":true,"theme":"dark"}""");

        HostConfig config = HostConfig.load(file);

        assertEquals(new HostConfig.Sunshine("https://localhost:47990", "me", "pw"), config.sunshine());
        assertTrue(config.monitor().turnOffWhenIdle());
        assertTrue(config.monitor().matchResolution());
        assertTrue(config.stream().placeWindows());
        assertEquals("dark", config.theme());

        config.save(file);
        String saved = Files.readString(file);
        assertFalse(saved.contains("sunshineUser"), saved);
        assertEquals(config, HostConfig.load(file));
    }

    @Test
    void fillsInDefaults() throws Exception {
        HostConfig config = HostConfig.load(dir.resolve("missing.json"));

        assertEquals(Protocol.DEFAULT_PORT, config.port());
        assertNull(config.advertiseName());
        assertEquals(HostConfig.DEFAULT_SUNSHINE_URL, config.sunshine().url());
        assertFalse(config.hasSunshineCredentials());
        assertFalse(config.monitor().turnOffWhenIdle());
        assertTrue(config.monitor().matchResolution());
        assertEquals(new HostConfig.Stream(true, true, true, true, true), config.stream());
        assertTrue(config.startInTray(), "starts in the tray, as before the setting");
    }

    @Test
    void keepsSwitchesTurnedOff() throws Exception {
        Path file = dir.resolve(HostConfig.FILE_NAME);
        Files.writeString(file, """
                {"monitor":{"matchResolution":false},"stream":{"launchSplash":false,"remoteNotices":false},
                 "startInTray":false}""");

        HostConfig config = HostConfig.load(file);

        assertFalse(config.startInTray());
        assertFalse(config.monitor().matchResolution());
        assertFalse(config.stream().launchSplash());
        assertFalse(config.stream().remoteNotices());
        assertTrue(config.stream().startNotice());
    }
}
