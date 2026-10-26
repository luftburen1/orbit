package orbit.host;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Where blocking work runs off the event thread, for {@code CompletableFuture}s: their default pool has about one
 * thread per core, and a stream start waits for minutes, so a few at once would hold up everything else in it.
 */
final class Background {

    static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private Background() {
    }
}
