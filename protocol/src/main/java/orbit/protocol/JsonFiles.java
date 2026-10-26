package orbit.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Reads and atomically writes the JSON config/state files of the host and agent. Each write goes to disk before it
 * replaces the file, and is kept in a {@code .bak} copy as well, which {@link #read} falls back to: a client that
 * loses power (a Raspberry Pi pulled from the wall) must not come back with an empty config and lose its token.
 */
public final class JsonFiles {

    private static final Logger LOG = Logger.getLogger(JsonFiles.class.getName());
    private static final ObjectMapper MAPPER = MessageCodec.newMapper();

    private JsonFiles() {
    }

    /** Orbit's per-user config directory: {@code %APPDATA%\orbit} on Windows, else {@code $XDG_CONFIG_HOME/orbit}. */
    public static Path configDir() {
        String appData = System.getenv("APPDATA");
        if (isWindows() && appData != null) {
            return Path.of(appData, "orbit");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("orbit");
    }

    /**
     * Reads {@code file}, or its {@code .bak} copy when the file is missing, empty or unreadable as JSON.
     *
     * @return empty when neither exists
     * @throws IOException when the file can't be read and there's no usable copy
     */
    public static <T> Optional<T> read(Path file, Class<T> type) throws IOException {
        Path backup = backupOf(file);
        if (!Files.exists(file)) {
            return Files.exists(backup) ? Optional.of(readBackup(file, backup, type, null)) : Optional.empty();
        }
        try {
            if (Files.size(file) == 0) {
                throw new IOException(file + " is empty");
            }
            return Optional.of(MAPPER.readValue(file.toFile(), type));
        } catch (IOException e) {
            if (!Files.exists(backup)) {
                throw e;
            }
            return Optional.of(readBackup(file, backup, type, e));
        }
    }

    private static <T> T readBackup(Path file, Path backup, Class<T> type, IOException problem) throws IOException {
        try {
            T value = MAPPER.readValue(backup.toFile(), type);
            LOG.warning(() -> "read " + backup + " instead of " + file
                    + (problem != null ? " (" + problem.getMessage() + ")" : " (missing)"));
            return value;
        } catch (IOException e) {
            if (problem != null) {
                problem.addSuppressed(e);
                throw problem;
            }
            throw e;
        }
    }

    /** The copy {@link #write} keeps of {@code file}. */
    public static Path backupOf(Path file) {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    /**
     * Writes {@code value} via a temp file and rename, readable only by the owner (files may hold secrets), then the
     * same to the {@code .bak} copy. Either is complete at any moment, so one of them survives a power cut.
     */
    public static void write(Path file, Object value) throws IOException {
        byte[] json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        replace(file, json);
        replace(backupOf(file), json);
    }

    private static void replace(Path file, byte[] content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
        try {
            restrictToOwner(tmp);
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            syncDirectory(dir);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Makes the rename itself durable; not possible on every OS (Windows can't open a directory), and then skipped. */
    private static void syncDirectory(Path dir) {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            // the rename is still atomic, only maybe not on disk yet
        }
    }

    private static void restrictToOwner(Path file) throws IOException {
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }
}
