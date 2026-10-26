package orbit.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonFilesTest {

    record Value(String name, int count) {
    }

    @TempDir
    Path dir;

    @Test
    void writeKeepsACopy() throws Exception {
        Path file = dir.resolve("state.json");
        JsonFiles.write(file, new Value("a", 1));
        assertEquals(Optional.of(new Value("a", 1)), JsonFiles.read(file, Value.class));
        assertEquals(Files.readString(file), Files.readString(JsonFiles.backupOf(file)));
    }

    @Test
    void missingFileIsEmpty() throws Exception {
        assertEquals(Optional.empty(), JsonFiles.read(dir.resolve("none.json"), Value.class));
    }

    @Test
    void emptyOrTruncatedFileFallsBackToTheCopy() throws Exception {
        Path file = dir.resolve("state.json");
        JsonFiles.write(file, new Value("a", 1));
        Files.writeString(file, "");
        assertEquals(Optional.of(new Value("a", 1)), JsonFiles.read(file, Value.class));
        Files.writeString(file, "{\"name\":\"a\",");
        assertEquals(Optional.of(new Value("a", 1)), JsonFiles.read(file, Value.class));
        Files.delete(file);
        assertEquals(Optional.of(new Value("a", 1)), JsonFiles.read(file, Value.class));
    }

    @Test
    void unreadableFileWithoutCopyFails() throws Exception {
        Path file = dir.resolve("state.json");
        Files.writeString(file, "{\"name\":");
        assertThrows(IOException.class, () -> JsonFiles.read(file, Value.class));
    }

    @Test
    void leavesNoTempFiles() throws Exception {
        Path file = dir.resolve("state.json");
        JsonFiles.write(file, new Value("a", 1));
        JsonFiles.write(file, new Value("b", 2));
        try (var files = Files.list(dir)) {
            assertTrue(files.noneMatch(f -> f.toString().endsWith(".tmp")));
        }
    }
}
