package orbit.agent;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Finds how to run Moonlight when {@code moonlightCommand} is not configured. */
final class MoonlightLocator {

    /** Executable names used by moonlight-qt packages, in order of preference. */
    static final List<String> EXECUTABLES = List.of("moonlight", "moonlight-qt");
    static final String FLATPAK_ID = "com.moonlight_stream.Moonlight";
    static final List<String> FLATPAK_COMMAND = List.of("flatpak", "run", FLATPAK_ID);
    /** Where the Windows installer puts Moonlight, under each of these folders. */
    static final List<String> WINDOWS_INSTALL_ROOTS = List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA");
    static final Path WINDOWS_INSTALL_DIR = Path.of("Moonlight Game Streaming", "Moonlight.exe");

    private MoonlightLocator() {
    }

    static Optional<List<String>> detect() {
        if (System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
            return detectWindows(System.getenv("PATH"), System::getenv);
        }
        return detect(System.getenv("PATH"), MoonlightLocator::flatpakInstalled);
    }

    /**
     * Moonlight.exe on the PATH, else where its installer puts it (it doesn't add itself to the PATH).
     *
     * @param env looks up an environment variable, null if unset
     */
    static Optional<List<String>> detectWindows(String path, UnaryOperator<String> env) {
        if (onPath(path, "Moonlight.exe")) {
            return Optional.of(List.of("Moonlight.exe"));
        }
        for (String root : WINDOWS_INSTALL_ROOTS) {
            String dir = env.apply(root);
            if (dir != null && !dir.isBlank()) {
                Path exe = Path.of(dir).resolve(WINDOWS_INSTALL_DIR);
                if (Files.isRegularFile(exe)) {
                    return Optional.of(List.of(exe.toString()));
                }
            }
        }
        return Optional.empty();
    }

    /** @param flatpakInstalled checks whether a Flatpak app id is installed, given the PATH to search */
    static Optional<List<String>> detect(String path, Predicate<String> flatpakInstalled) {
        for (String name : EXECUTABLES) {
            if (onPath(path, name)) {
                return Optional.of(List.of(name));
            }
        }
        if (onPath(path, "flatpak") && flatpakInstalled.test(FLATPAK_ID)) {
            return Optional.of(FLATPAK_COMMAND);
        }
        return Optional.empty();
    }

    static boolean onPath(String path, String name) {
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, name))) {
                return true;
            }
        }
        return false;
    }

    private static boolean flatpakInstalled(String appId) {
        try {
            Process p = new ProcessBuilder("flatpak", "info", appId)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
