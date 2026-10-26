package orbit.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

@DisabledOnOs(OS.WINDOWS)
class CommandsTest {

    @Test
    void returnsOutputAndExitCode() throws Exception {
        Commands.Result result = Commands.run(List.of("sh", "-c", "echo out; echo err >&2; exit 3"),
                Duration.ofSeconds(5));
        assertEquals(3, result.exitCode());
        assertFalse(result.ok());
        assertTrue(result.output().contains("out"));
        assertTrue(result.output().contains("err"));
    }

    @Test
    void killsACommandThatHangsWithItsOutputOpen() {
        long start = System.nanoTime();
        assertThrows(IOException.class, () -> Commands.run(List.of("sh", "-c", "echo started; sleep 30"),
                Duration.ofMillis(300)));
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(5).toNanos());
    }

    @Test
    void doesNotWaitForAChildThatKeepsTheOutputOpen() throws Exception {
        long start = System.nanoTime();
        Commands.Result result = Commands.run(List.of("sh", "-c", "sleep 30 & echo done"), Duration.ofSeconds(5));
        assertTrue(result.ok());
        assertTrue(result.output().contains("done"));
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(10).toNanos());
    }

    @Test
    void failsForAMissingCommand() {
        assertThrows(IOException.class, () -> Commands.run(List.of("orbit-no-such-command"), Duration.ofSeconds(5)));
    }
}
