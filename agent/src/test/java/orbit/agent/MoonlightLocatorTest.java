package orbit.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MoonlightLocatorTest {

    @TempDir
    Path bin;

    @TempDir
    Path otherBin;

    private void executable(Path dir, String name) throws Exception {
        Path file = Files.createFile(dir.resolve(name));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private String path(Path... dirs) {
        return String.join(File.pathSeparator, java.util.Arrays.stream(dirs).map(Path::toString).toList());
    }

    @Test
    void findsMoonlightQt() throws Exception {
        executable(bin, "moonlight-qt");
        assertEquals(Optional.of(List.of("moonlight-qt")), MoonlightLocator.detect(path(otherBin, bin), id -> false));
    }

    @Test
    void prefersMoonlight() throws Exception {
        executable(bin, "moonlight-qt");
        executable(otherBin, "moonlight");
        assertEquals(Optional.of(List.of("moonlight")), MoonlightLocator.detect(path(bin, otherBin), id -> false));
    }

    @Test
    void fallsBackToFlatpak() throws Exception {
        executable(bin, "flatpak");
        assertEquals(Optional.of(MoonlightLocator.FLATPAK_COMMAND),
                MoonlightLocator.detect(path(bin), MoonlightLocator.FLATPAK_ID::equals));
        assertEquals(Optional.empty(), MoonlightLocator.detect(path(bin), id -> false), "flatpak without Moonlight");
    }

    @Test
    void ignoresNonExecutables() throws Exception {
        Files.createFile(bin.resolve("moonlight"));
        assertTrue(MoonlightLocator.detect(path(bin), id -> false).isEmpty());
        assertTrue(MoonlightLocator.detect(null, id -> true).isEmpty());
    }

    @Test
    void findsMoonlightExeOnTheWindowsPath() throws Exception {
        executable(bin, "Moonlight.exe");
        assertEquals(Optional.of(List.of("Moonlight.exe")),
                MoonlightLocator.detectWindows(path(otherBin, bin), name -> null));
    }

    @Test
    void findsMoonlightWhereTheWindowsInstallerPutsIt() throws Exception {
        Path exe = otherBin.resolve(MoonlightLocator.WINDOWS_INSTALL_DIR);
        Files.createDirectories(exe.getParent());
        Files.createFile(exe);
        java.util.Map<String, String> env = java.util.Map.of("ProgramFiles", bin.toString(),
                "ProgramFiles(x86)", otherBin.toString());
        assertEquals(Optional.of(List.of(exe.toString())), MoonlightLocator.detectWindows(path(bin), env::get));
        assertEquals(Optional.empty(), MoonlightLocator.detectWindows(path(bin), name -> null), "not installed");
    }
}
