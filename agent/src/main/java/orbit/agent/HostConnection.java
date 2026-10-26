package orbit.agent;

import java.net.URI;
import java.nio.ByteBuffer;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import orbit.protocol.Message;
import orbit.protocol.MessageCodec;
import orbit.protocol.Protocol;
import orbit.protocol.ProtocolException;

/**
 * Keeps a WebSocket to the host open: locates the host, connects, and on loss reconnects with exponential backoff,
 * re-running discovery each time since the host's address may have changed.
 */
public final class HostConnection implements Runnable {

    private static final Logger LOG = Logger.getLogger(HostConnection.class.getName());
    private static final Duration MIN_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    /** A connection that lasted this long resets the backoff. */
    private static final Duration STABLE = Duration.ofMinutes(1);
    /**
     * The host pings every 15 s. Hearing nothing for this long means it is gone without closing the connection
     * (power loss, network down), which TCP alone may not notice for many minutes.
     */
    static final Duration SILENCE_TIMEOUT = Duration.ofSeconds(45);
    /** Messages are small; one that can't be sent in this long means the connection is stuck. */
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    /** Callbacks, invoked on the WebSocket's thread; implementations must not block. */
    public interface Handler {
        /** Connection is open; return the hello to send. */
        Message.Hello connected(HostLocator.Target target);

        void received(Message.FromHost message);

        void disconnected();
    }

    private final HostLocator locator;
    private final Handler handler;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile boolean running = true;
    private volatile WebSocket current;
    /** Completes when {@link #current} closes; completed early to drop a stuck connection. */
    private volatile CompletableFuture<Void> currentClosed;

    public HostConnection(HostLocator locator, Handler handler) {
        this.locator = locator;
        this.handler = handler;
    }

    /**
     * Sends a message if connected; messages sent while disconnected, or on a connection that's failing, are dropped
     * (and logged), so callers on the worker thread go on either way. A send that can't get out within
     * {@link #SEND_TIMEOUT} drops the connection, which then reconnects.
     */
    public void send(Message.FromAgent message) {
        WebSocket ws = current;
        if (ws == null) {
            LOG.fine(() -> "not connected, dropping " + message);
            return;
        }
        synchronized (this) {
            try {
                ws.sendText(MessageCodec.encode(message), true).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException | IllegalStateException e) {
                LOG.info(() -> "cannot send " + message.getClass().getSimpleName() + " to the host: "
                        + (e instanceof ExecutionException ? e.getCause() : e));
            } catch (TimeoutException e) {
                LOG.warning(() -> "sending to the host takes over " + SEND_TIMEOUT.toSeconds()
                        + " s; dropping the connection");
                ws.abort();
                CompletableFuture<Void> closed = currentClosed;
                if (closed != null) {
                    closed.complete(null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isConnected() {
        return current != null;
    }

    public void stop() {
        running = false;
        WebSocket ws = current;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "agent stopping");
        }
    }

    @Override
    public void run() {
        Duration backoff = MIN_BACKOFF;
        while (running) {
            long started = System.nanoTime();
            var target = locator.locate();
            if (target.isEmpty()) {
                LOG.info("no Orbit host found, retrying in " + backoff.toSeconds() + "s");
            } else {
                connectAndWait(target.get());
            }
            if (!running) {
                break;
            }
            backoff = System.nanoTime() - started > STABLE.toNanos() ? MIN_BACKOFF : min(backoff.multipliedBy(2));
            try {
                Thread.sleep(backoff.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void connectAndWait(HostLocator.Target target) {
        URI uri = URI.create("ws://" + hostForUri(target.address()) + ":" + target.port() + Protocol.AGENT_PATH);
        Listener listener = new Listener();
        try {
            WebSocket ws = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(uri, listener)
                    .get(15, TimeUnit.SECONDS);
            LOG.info(() -> "connected to " + uri);
            currentClosed = listener.closed;
            current = ws;
            send(handler.connected(target));
            awaitClose(ws, listener, uri);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            LOG.info(() -> "cannot connect to " + uri + ": "
                    + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.log(Level.INFO, "connection to " + uri + " failed", e);
        } finally {
            if (current != null) {
                current = null;
                handler.disconnected();
            }
        }
    }

    /** Waits until the connection closes, or drops it when the host has gone silent. */
    private void awaitClose(WebSocket ws, Listener listener, URI uri) throws InterruptedException, ExecutionException {
        while (true) {
            try {
                listener.closed.get(5, TimeUnit.SECONDS);
                return;
            } catch (TimeoutException e) {
                long silentMillis = (System.nanoTime() - listener.lastHeard) / 1_000_000;
                if (silentMillis > SILENCE_TIMEOUT.toMillis()) {
                    LOG.warning(() -> "no word from " + uri + " for " + silentMillis / 1000
                            + " s; dropping the connection");
                    ws.abort();
                    return;
                }
            }
        }
    }

    private final class Listener implements WebSocket.Listener {
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();
        /** {@link System#nanoTime} of the last message or ping from the host. */
        volatile long lastHeard = System.nanoTime();

        @Override
        public CompletionStage<?> onPing(WebSocket ws, ByteBuffer message) {
            lastHeard = System.nanoTime();
            ws.request(1); // the client answers pings with pongs by itself
            return null;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            lastHeard = System.nanoTime();
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                try {
                    if (MessageCodec.decode(text) instanceof Message.FromHost m) {
                        if (m instanceof Message.Rejected r) {
                            LOG.warning(() -> "host rejected this agent: " + r.reason());
                        }
                        handler.received(m);
                    }
                } catch (ProtocolException e) {
                    LOG.warning(() -> "ignoring bad message from host: " + e.getMessage());
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            LOG.info(() -> "host closed the connection (" + statusCode + (reason.isEmpty() ? "" : ": " + reason) + ")");
            closed.complete(null);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            LOG.info(() -> "connection error: " + error);
            closed.complete(null);
        }
    }

    private static String hostForUri(String address) {
        return address.contains(":") ? "[" + address + "]" : address;
    }

    private static Duration min(Duration d) {
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }
}
