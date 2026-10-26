package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Clock;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import orbit.protocol.ClientState;
import orbit.protocol.Message;
import orbit.protocol.MessageCodec;
import orbit.protocol.Protocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the real server in-process with a fake agent speaking WebSocket. */
class AgentServerIT {

    private AgentRegistry registry;
    private AgentServer server;
    private int port;

    @BeforeEach
    void start() {
        registry = new AgentRegistry(null, Clock.systemUTC());
        server = new AgentServer(registry);
        port = server.start(0);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    /** Minimal agent: queues every message it receives. */
    static final class FakeAgent implements WebSocket.Listener {
        final BlockingQueue<Message> inbox = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();
        WebSocket ws;

        static FakeAgent connect(int port) throws Exception {
            FakeAgent agent = new FakeAgent();
            agent.ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + Protocol.AGENT_PATH), agent)
                    .get(5, TimeUnit.SECONDS);
            return agent;
        }

        void send(Message message) throws Exception {
            ws.sendText(MessageCodec.encode(message), true).get(5, TimeUnit.SECONDS);
        }

        <T extends Message> T expect(Class<T> type) throws Exception {
            Message m = inbox.poll(5, TimeUnit.SECONDS);
            assertNotNull(m, "timed out waiting for " + type.getSimpleName());
            return assertInstanceOf(type, m);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                try {
                    inbox.add(MessageCodec.decode(partial.toString()));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }
    }

    private static Message.Hello hello(String token) {
        return new Message.Hello(Protocol.VERSION, "agent-1", "bedroom", token, null);
    }

    private void awaitClient(java.util.function.Predicate<ClientView> condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (registry.clients().stream().anyMatch(condition)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("condition not met; clients=" + registry.clients());
    }

    @Test
    void approveStatusAndStreamCommand() throws Exception {
        FakeAgent agent = FakeAgent.connect(port);
        agent.send(hello(null));
        agent.expect(Message.Pending.class);

        registry.approve("agent-1");
        String token = agent.expect(Message.Welcome.class).token();
        assertNotNull(token);

        agent.send(new Message.Status(ClientState.AVAILABLE, null));
        awaitClient(c -> c.state() == ClientState.AVAILABLE);

        CompletableFuture<Message.CommandResult> result = registry.startStream("agent-1", "Desktop");
        Message.StartStream start = agent.expect(Message.StartStream.class);
        assertEquals("Desktop", start.app());
        agent.send(Message.CommandResult.success(start.cmdId()));
        assertTrue(result.get(5, TimeUnit.SECONDS).ok());

        agent.ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
        awaitClient(c -> !c.online());

        FakeAgent again = FakeAgent.connect(port);
        again.send(hello(token));
        again.expect(Message.Welcome.class);
        awaitClient(ClientView::online);
    }

    @Test
    void messageBeforeHelloIsRejected() throws Exception {
        FakeAgent agent = FakeAgent.connect(port);
        agent.send(new Message.Status(ClientState.AVAILABLE, null));
        agent.expect(Message.Rejected.class);
        agent.closed.get(5, TimeUnit.SECONDS);
    }

    @Test
    void garbageIsRejected() throws Exception {
        FakeAgent agent = FakeAgent.connect(port);
        agent.ws.sendText("{nope", true).get(5, TimeUnit.SECONDS);
        agent.expect(Message.Rejected.class);
        agent.closed.get(5, TimeUnit.SECONDS);
    }
}
