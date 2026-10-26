package orbit.host;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import orbit.protocol.ClientState;

/** Which actions a client allows right now. Shared by the window's toolbar and the tray menu. */
final class ClientActions {

    enum Action { APPROVE, PAIR, UNPAIR, START, STOP, SETTINGS, REMOVE }

    private ClientActions() {
    }

    /** @param busy Orbit is already doing something to the client, e.g. stopping its stream */
    static Set<Action> enabled(ClientView c, boolean busy) {
        return enabled(c, busy, true);
    }

    /**
     * @param busy          Orbit is already doing something to the client, e.g. stopping its stream
     * @param streamSlotFree Sunshine can take another stream (see {@link #streamLimit})
     */
    static Set<Action> enabled(ClientView c, boolean busy, boolean streamSlotFree) {
        if (c.agentless()) {
            // No agent to control: Orbit can only pair a waiting request with the PIN it shows, or remove a pairing,
            // which also takes it off the list.
            return c.sunshineDown() || busy ? EnumSet.noneOf(Action.class)
                    : EnumSet.of(c.pairingRequest() != null ? Action.PAIR : Action.UNPAIR);
        }
        Set<Action> actions = EnumSet.of(Action.REMOVE);
        boolean streaming = c.streaming();
        boolean ready = c.online() && c.approved() && !c.moonlightMissing() && !c.sunshineDown();
        if (!c.approved()) {
            actions.add(Action.APPROVE);
        } else {
            actions.add(Action.SETTINGS);
        }
        // Only when the client says it isn't paired: an error (e.g. Moonlight can't reach Sunshine) isn't fixed by
        // pairing, and the status already says what's wrong.
        // Disabled in Sunshine, Moonlight takes it for unpaired, but pairing again isn't the fix: enabling it is.
        if (ready && c.state() == ClientState.UNPAIRED && !c.disabledInSunshine() && !busy) {
            actions.add(Action.PAIR);
        } else if (c.approved() && c.pairedInSunshine() && !c.sunshineDown() && !busy) {
            // Done in Sunshine alone, so also for an offline client.
            actions.add(Action.UNPAIR);
        }
        // Only once paired and ready: unpaired, moonlight-qt would sit on a "pair first" message.
        // Someone at the client turned streams off in its tray; the agent would refuse anyway. A client disabled in
        // Sunshine would be refused by Sunshine.
        if (ready && c.state() == ClientState.AVAILABLE && !busy && streamSlotFree && !c.streamsBlocked()
                && !c.disabledInSunshine()) {
            actions.add(Action.START);
        }
        if (ready && streaming && !busy) {
            actions.add(Action.STOP);
        }
        return actions;
    }

    /**
     * Why {@code c} can't start a stream because Sunshine already streams to as many clients as its {@code channels}
     * setting allows, e.g. "Sunshine streams to 1 client at a time"; null if it can. Its own stream doesn't count.
     */
    static String streamLimit(List<ClientView> clients, ClientView c, int maxStreams) {
        long others = clients.stream()
                .filter(o -> !o.agentId().equals(c.agentId()) && o.streaming())
                .count();
        return others < maxStreams ? null
                : "Sunshine streams to " + maxStreams + (maxStreams == 1 ? " client" : " clients") + " at a time";
    }

    /**
     * Why a stream {@code c} asked for (e.g. with its TV remote) can't start now, in words for its idle screen; null
     * if Start would be allowed.
     *
     * @param limit from {@link #streamLimit}, or null
     */
    static String startRefusal(ClientView c, boolean busy, String limit) {
        if (enabled(c, busy, limit == null).contains(Action.START)) {
            return null;
        }
        if (c.streaming()) {
            return "already streaming";
        }
        if (c.sunshineDown()) {
            return "Sunshine isn't running on the host";
        }
        if (busy) {
            return "Orbit is busy with this client";
        }
        if (c.disabledInSunshine()) {
            return "this client is disabled in Sunshine";
        }
        if (limit != null) {
            return limit;
        }
        if (c.state() == ClientState.UNPAIRED) {
            return "not paired yet";
        }
        return "this client can't stream right now";
    }

    /**
     * {@code limit} (from {@link #streamLimit}) if it's the only thing keeping {@code c} from starting a stream, so
     * the greyed-out Start can say why; else null.
     */
    static String onlyStreamLimit(ClientView c, boolean busy, String limit) {
        return limit != null && enabled(c, busy).contains(Action.START) ? limit : null;
    }
}
