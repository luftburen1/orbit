package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppIdsTest {

    @TempDir
    Path dir;

    @Test
    void ignoresNoAppAndUnknownIds() {
        AppIds ids = new AppIds();
        ids.learn(0, "Desktop");
        ids.learn(-1, "Desktop");
        assertNull(ids.name(0));
        assertNull(ids.name(-1));
    }

    @Test
    void aNewIdReplacesTheOldOne() {
        AppIds ids = new AppIds();
        ids.learn(7, "Zwift");
        ids.learn(8, "Zwift");
        assertNull(ids.name(7));
        assertEquals("Zwift", ids.name(8));
    }

    @Test
    void inMemoryUntilToldWhereToSave() throws Exception {
        Path file = dir.resolve("app-ids.json");
        AppIds ids = new AppIds();
        ids.learn(3, "Kodi");
        assertFalse(Files.exists(file));

        ids.rememberIn(file);
        ids.learn(4, "Steam Big Picture");
        AppIds later = new AppIds();
        later.rememberIn(file);
        assertEquals("Kodi", later.name(3));
        assertEquals("Steam Big Picture", later.name(4));
    }

    @Test
    void skipsIdsThatArentNumbers() throws Exception {
        Path file = dir.resolve("app-ids.json");
        Files.writeString(file, "{\"apps\":{\"x\":\"Broken\",\"5\":\"Desktop\"}}");
        AppIds ids = new AppIds();
        ids.rememberIn(file);
        assertEquals("Desktop", ids.name(5));
    }
}
