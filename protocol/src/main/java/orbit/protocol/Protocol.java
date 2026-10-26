package orbit.protocol;

/** Constants shared by the host and agents. */
public final class Protocol {
    /** Bumped on incompatible message changes; checked during the {@link Message.Hello} handshake. */
    public static final int VERSION = 1;

    /** mDNS service type the host advertises. */
    public static final String MDNS_SERVICE_TYPE = "_orbit._tcp.local.";

    /** Default TCP port of the host's agent endpoint. */
    public static final int DEFAULT_PORT = 47_900;

    /** WebSocket path agents connect to. */
    public static final String AGENT_PATH = "/agent";

    private Protocol() {
    }
}
