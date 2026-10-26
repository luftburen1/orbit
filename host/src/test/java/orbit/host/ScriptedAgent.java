package orbit.host;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import orbit.protocol.ClientState;
import orbit.protocol.Message;
import orbit.protocol.Protocol;

/** Approved fake agent that answers commands like a real one and logs them to {@code events}. */
final class ScriptedAgent implements AgentRegistry.AgentLink {
    private final AgentRegistry registry;
    private final List<String> events;
    final String id;
    /** Stream starts that fail before one succeeds. */
    final AtomicInteger failingStarts = new AtomicInteger();
    /** Completed when the agent may answer a stream start; replace it to keep a start pending. */
    volatile CompletableFuture<Void> startAnswer = CompletableFuture.completedFuture(null);
    /** {@code quitAppOnFailure} of the last StartStream, or null before one. */
    volatile Boolean quitAppOnFailure;

    ScriptedAgent(AgentRegistry registry, List<String> events, String id, ClientState state, String app) {
        this.registry = registry;
        this.events = events;
        this.id = id;
        registry.connected(new Message.Hello(Protocol.VERSION, id, id, null, null), this);
        registry.approve(id);
        registry.received(id, this, new Message.Status(state, app));
    }

    /** Reports a state change, as the agent does when Moonlight exits. */
    void report(ClientState state, String app) {
        registry.received(id, this, new Message.Status(state, app));
    }

    @Override
    public void send(Message.FromHost message) {
        switch (message) {
            case Message.StopStream s -> {
                events.add((s.disconnectOnly() ? "disconnect " : "stop ") + id);
                reply(s.cmdId(), null, new Message.Status(ClientState.AVAILABLE, null));
            }
            case Message.StartStream s -> {
                events.add("start " + id + " " + s.app() + " " + s.settings().resolution());
                quitAppOnFailure = s.quitAppOnFailure();
                startAnswer.thenRun(() -> {
                    if (failingStarts.getAndDecrement() > 0) {
                        reply(s.cmdId(), "host not ready", null);
                    } else {
                        reply(s.cmdId(), null, new Message.Status(ClientState.STREAMING, s.app()));
                    }
                });
            }
            case Message.CheckPairing c -> events.add("check pairing " + id);
            default -> {
            }
        }
    }

    private void reply(String cmdId, String error, Message.Status status) {
        CompletableFuture.runAsync(() -> {
            if (status != null) {
                registry.received(id, this, status);
            }
            registry.received(id, this, error == null
                    ? Message.CommandResult.success(cmdId) : Message.CommandResult.failure(cmdId, error));
        });
    }

    @Override
    public void close(Message.FromHost goodbye) {
    }
}
