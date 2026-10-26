package orbit.host;

import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import orbit.protocol.Message;
import orbit.protocol.MessageCodec;
import orbit.protocol.Protocol;
import orbit.protocol.ProtocolException;

/** WebSocket endpoint agents connect to. Translates frames into {@link AgentRegistry} calls. */
public final class AgentServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(AgentServer.class.getName());
    private static final Duration HELLO_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PING_INTERVAL = Duration.ofSeconds(15);

    /**
     * Per-connection state; {@code agentId} is set once the handshake is accepted. Sends go out in order on the
     * connection's own thread: Jetty's send blocks while a client doesn't read, and the registry sends with its lock
     * held, so one stuck client would otherwise hold up every other one and the window.
     */
    private static final class Session implements AgentRegistry.AgentLink {
        final WsContext ctx;
        volatile String agentId;
        private final ExecutorService outbox = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("orbit-agent-send").factory());

        Session(WsContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void send(Message.FromHost message) {
            String text = MessageCodec.encode(message);
            enqueue(() -> {
                if (ctx.session.isOpen()) {
                    ctx.send(text);
                }
            });
        }

        @Override
        public void close(Message.FromHost goodbye) {
            if (goodbye != null) {
                send(goodbye);
            }
            String reason = goodbye instanceof Message.Rejected r ? truncate(r.reason()) : "bye";
            enqueue(() -> ctx.closeSession(1000, reason));
        }

        private void enqueue(Runnable action) {
            try {
                outbox.execute(() -> {
                    try {
                        action.run();
                    } catch (RuntimeException e) {
                        LOG.log(Level.FINE, "cannot send to " + remoteAddress(), e);
                    }
                });
            } catch (RejectedExecutionException e) {
                // The connection has closed; nothing more goes out on it.
            }
        }

        /** Once the connection has closed: whatever is still queued goes nowhere. */
        void closed() {
            outbox.shutdown();
        }

        @Override
        public String remoteAddress() {
            return ctx.session.getRemoteAddress() instanceof InetSocketAddress a && a.getAddress() != null
                    ? a.getAddress().getHostAddress() : null;
        }

        /** Close reasons are limited to 123 bytes. */
        private static String truncate(String reason) {
            return reason.length() > 100 ? reason.substring(0, 100) : reason;
        }
    }

    private final AgentRegistry registry;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("orbit-hello-timeout").factory());
    private Javalin app;

    public AgentServer(AgentRegistry registry) {
        this.registry = registry;
    }

    /** Starts listening on all interfaces; {@code port} 0 picks a free port. Returns the bound port. */
    public int start(int port) {
        app = Javalin.create(config -> config.showJavalinBanner = false)
                .ws(Protocol.AGENT_PATH, ws -> {
                    ws.onConnect(this::onConnect);
                    ws.onMessage(ctx -> onMessage(ctx, ctx.message()));
                    ws.onClose(this::onClose);
                    ws.onError(ctx -> LOG.log(Level.FINE, "websocket error", ctx.error()));
                })
                .start(port);
        LOG.info(() -> "agent endpoint listening on port " + app.port());
        return app.port();
    }

    private void onConnect(WsContext ctx) {
        Session session = new Session(ctx);
        sessions.put(ctx.sessionId(), session);
        ctx.enableAutomaticPings(PING_INTERVAL.toSeconds(), TimeUnit.SECONDS);
        timer.schedule(() -> {
            if (session.agentId == null && sessions.containsKey(ctx.sessionId())) {
                session.close(new Message.Rejected("no hello within " + HELLO_TIMEOUT.toSeconds() + "s"));
            }
        }, HELLO_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void onMessage(WsContext ctx, String text) {
        Session session = sessions.get(ctx.sessionId());
        if (session == null) {
            return;
        }
        Message message;
        try {
            message = MessageCodec.decode(text);
        } catch (ProtocolException e) {
            LOG.warning(() -> "closing connection from " + ctx.session.getRemoteAddress() + ": " + e.getMessage());
            session.close(new Message.Rejected(e.getMessage()));
            return;
        }
        if (!(message instanceof Message.FromAgent fromAgent)) {
            session.close(new Message.Rejected("unexpected message type"));
            return;
        }
        if (session.agentId == null) {
            if (fromAgent instanceof Message.Hello hello) {
                if (registry.connected(hello, session)) {
                    session.agentId = hello.agentId();
                }
            } else {
                session.close(new Message.Rejected("expected hello"));
            }
            return;
        }
        registry.received(session.agentId, session, fromAgent);
    }

    private void onClose(WsContext ctx) {
        Session session = sessions.remove(ctx.sessionId());
        if (session == null) {
            return;
        }
        session.closed();
        if (session.agentId != null) {
            registry.disconnected(session.agentId, session);
        }
    }

    @Override
    public void close() {
        timer.shutdownNow();
        if (app != null) {
            app.stop();
        }
    }
}
