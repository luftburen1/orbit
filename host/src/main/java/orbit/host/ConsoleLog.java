package orbit.host;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Keeps the last lines Orbit printed to the console (its log and the web server's), for the log window. Installed
 * first thing in {@code main}, because loggers keep the console stream they find when they start.
 */
final class ConsoleLog {

    static final int MAX_LINES = 5000;

    private static final ConsoleLog INSTANCE = new ConsoleLog(MAX_LINES);

    private final int maxLines;
    private final Deque<String> lines = new ArrayDeque<>();
    /** Changes whenever the lines change, so a viewer can tell when to redraw. */
    private long version;

    ConsoleLog(int maxLines) {
        this.maxLines = maxLines;
    }

    static ConsoleLog get() {
        return INSTANCE;
    }

    /** Copies everything written to {@code System.out} and {@code System.err} into the log, still printing it. */
    static void install() {
        System.setOut(INSTANCE.tee(System.out));
        System.setErr(INSTANCE.tee(System.err));
    }

    PrintStream tee(PrintStream console) {
        Charset charset = console.charset();
        OutputStream copy = new OutputStream() {
            private final ByteArrayOutputStream line = new ByteArrayOutputStream();

            @Override
            public synchronized void write(int b) {
                console.write(b);
                collect(b);
            }

            @Override
            public synchronized void write(byte[] b, int off, int len) {
                console.write(b, off, len);
                for (int i = off; i < off + len; i++) {
                    collect(b[i]);
                }
            }

            @Override
            public void flush() {
                console.flush();
            }

            private void collect(int b) {
                if (b == '\n') {
                    add(line.toString(charset));
                    line.reset();
                } else if (b != '\r') {
                    line.write(b);
                }
            }
        };
        return new PrintStream(copy, true, charset);
    }

    synchronized void add(String text) {
        lines.addLast(text);
        if (lines.size() > maxLines) {
            lines.removeFirst();
        }
        version++;
    }

    synchronized long version() {
        return version;
    }

    synchronized String text() {
        return String.join("\n", lines);
    }

    synchronized void clear() {
        lines.clear();
        version++;
    }
}
