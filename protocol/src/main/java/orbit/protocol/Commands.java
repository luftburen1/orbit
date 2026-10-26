package orbit.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs short-lived external commands (e.g. {@code kscreen-doctor}, {@code vainfo}) with a timeout that holds: the
 * output is read on its own thread, so a command that hangs is killed instead of blocking its caller.
 */
public final class Commands {

    /** How long the output may still come after the command exited, e.g. from a child it left running. */
    private static final Duration OUTPUT_GRACE = Duration.ofSeconds(2);

    private Commands() {
    }

    /** What a command printed (stdout and stderr together), and how it exited. */
    public record Result(int exitCode, String output) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    /**
     * Runs {@code command} and waits for it.
     *
     * @throws IOException if it can't be started, or didn't finish within {@code timeout} (it's killed then)
     */
    public static Result run(List<String> command, Duration timeout) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().name("command-output").start(() -> drain(p.getInputStream(), output));
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new IOException(command.getFirst() + " didn't finish within " + timeout.toMillis() / 1000.0
                        + " s");
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            throw e;
        }
        reader.join(OUTPUT_GRACE);
        synchronized (output) {
            return new Result(p.exitValue(), output.toString(StandardCharsets.UTF_8));
        }
    }

    private static void drain(InputStream in, ByteArrayOutputStream into) {
        byte[] buffer = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                synchronized (into) {
                    into.write(buffer, 0, n);
                }
            }
        } catch (IOException e) {
            // the process went away
        }
    }
}
