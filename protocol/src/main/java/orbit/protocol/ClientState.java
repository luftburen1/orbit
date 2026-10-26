package orbit.protocol;

/** What an agent reports about its client. "Offline" is not reported; the host derives it from the connection. */
public enum ClientState {
    /** Online, paired and not streaming: ready to stream to. */
    AVAILABLE,
    /** A Moonlight stream is running. */
    STREAMING,
    /** Moonlight is not paired with this host yet. */
    UNPAIRED,
    /** Moonlight is missing or misbehaving; see the agent log. */
    ERROR
}
