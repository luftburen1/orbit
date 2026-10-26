package orbit.host;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AppInfoTest {

    @Test
    void versionComesFromTheBuild() {
        assertTrue(AppInfo.VERSION.matches("\\d+\\.\\d+\\.\\d+.*"), AppInfo.VERSION);
    }
}
