package orbit.host;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import orbit.protocol.ClientState;

/**
 * What Orbit is doing to each client right now, e.g. "Stopping…", by agent id. Shown instead of the client's status,
 * and a client with an activity takes no other action until it ends. Not thread-safe; the window uses it on the event
 * thread.
 */
final class ClientActivities {

    static final String STARTING = "Starting…";
    static final String STOPPING = "Stopping…";
    static final String PAIRING = "Pairing…";
    static final String UNPAIRING = "Unpairing…";
    /** While a streaming client reconnects, for new settings or a monitor switch; it stops streaming meanwhile. */
    static final String RECONNECTING = "Reconnecting…";

    private final Map<String, String> byClient = new HashMap<>();

    /** The client's activity, or null. */
    String get(String agentId) {
        return byClient.get(agentId);
    }

    boolean busy(String agentId) {
        return byClient.containsKey(agentId);
    }

    /** Starts {@code activity}, unless the client already has one; false if it has. */
    boolean begin(String agentId, String activity) {
        return byClient.putIfAbsent(agentId, activity) == null;
    }

    /** Ends {@code activity}; false if the client was doing something else, or nothing. */
    boolean end(String agentId, String activity) {
        return byClient.remove(agentId, activity);
    }

    /** Turns {@code from} into {@code to}; false if the client wasn't doing {@code from}. */
    boolean replace(String agentId, String from, String to) {
        return byClient.replace(agentId, from, to);
    }

    /**
     * Ends the activities the clients' reported state shows are over: a stop once the client no longer streams (or is
     * gone); a start once it streams (or is gone); an unpairing once the client says it isn't paired (or is gone, so
     * it can't say), or for a pairing without an agent once Sunshine no longer lists it. The others end when their
     * command answers.
     *
     * @return true if any ended
     */
    boolean settle(List<ClientView> clients) {
        return byClient.entrySet().removeIf(e -> {
            Optional<ClientView> c = clients.stream()
                    .filter(v -> v.agentId().equals(e.getKey()) && (v.online() || v.agentless()))
                    .findFirst();
            boolean streaming = c.map(ClientView::streaming).orElse(false);
            return switch (e.getValue()) {
                case STOPPING -> !streaming;
                case STARTING -> streaming || c.isEmpty();
                case UNPAIRING -> c.isEmpty() || c.get().state() == ClientState.UNPAIRED;
                default -> false;
            };
        });
    }
}
