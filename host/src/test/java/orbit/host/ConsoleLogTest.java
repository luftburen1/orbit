package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ConsoleLogTest {

    @Test
    void keepsWholeLinesAndStillPrintsThem() {
        ConsoleLog log = new ConsoleLog(10);
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        PrintStream out = log.tee(new PrintStream(console, true, StandardCharsets.UTF_8));

        out.print("INFO: agent livingroom ");
        assertEquals("", log.text(), "a line is kept once it ends");
        out.println("connected");
        out.print("windows line\r\nåäö ✓\n");

        assertEquals("INFO: agent livingroom connected\nwindows line\nåäö ✓", log.text());
        assertEquals("INFO: agent livingroom connected\nwindows line\r\nåäö ✓\n",
                console.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "\n"));
    }

    @Test
    void keepsOnlyTheLastLines() {
        ConsoleLog log = new ConsoleLog(3);
        for (int i = 1; i <= 5; i++) {
            log.add("line " + i);
        }
        assertEquals("line 3\nline 4\nline 5", log.text());
        long before = log.version();
        log.clear();
        assertEquals("", log.text());
        assertNotEquals(before, log.version(), "viewers see the clear");
    }
}
