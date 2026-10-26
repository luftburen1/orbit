package orbit.agent;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import orbit.protocol.Network;
import orbit.protocol.Protocol;

/** Finds the Orbit host: the configured address if any, otherwise via mDNS. */
public final class HostLocator {

    private static final Logger LOG = Logger.getLogger(HostLocator.class.getName());
    private static final Duration BROWSE_TIMEOUT = Duration.ofSeconds(6);

    /** Where to reach the host. {@code address} is also the Sunshine host Moonlight connects to. */
    public record Target(String address, int port) {
        @Override
        public String toString() {
            return address + ":" + port;
        }
    }

    private final String configuredHost;
    private final String wantedName;

    public HostLocator(String configuredHost, String wantedName) {
        this.configuredHost = configuredHost;
        this.wantedName = wantedName;
    }

    public Optional<Target> locate() {
        if (configuredHost != null) {
            return Optional.of(parse(configuredHost));
        }
        return discover();
    }

    /** Parses {@code host}, {@code host:port}, {@code [v6]} or {@code [v6]:port}. */
    static Target parse(String hostAndPort) {
        String s = hostAndPort.strip();
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            String host = s.substring(1, end);
            String rest = s.substring(end + 1);
            return new Target(host, rest.startsWith(":") ? Integer.parseInt(rest.substring(1)) : Protocol.DEFAULT_PORT);
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon) {
            return new Target(s.substring(0, colon), Integer.parseInt(s.substring(colon + 1)));
        }
        return new Target(s, Protocol.DEFAULT_PORT);
    }

    private Optional<Target> discover() {
        InetAddress bind = Network.preferredLanAddress().orElse(null);
        try (JmDNS jmdns = JmDNS.create(bind)) {
            ServiceInfo[] found = jmdns.list(Protocol.MDNS_SERVICE_TYPE, BROWSE_TIMEOUT.toMillis());
            return Arrays.stream(found)
                    .filter(info -> wantedName == null || wantedName.equalsIgnoreCase(info.getName()))
                    .filter(info -> info.getInet4Addresses().length > 0 || info.getInetAddresses().length > 0)
                    .findFirst()
                    .map(info -> {
                        InetAddress a = info.getInet4Addresses().length > 0
                                ? info.getInet4Addresses()[0] : info.getInetAddresses()[0];
                        Target t = new Target(a.getHostAddress(), info.getPort());
                        LOG.info(() -> "found Orbit host '" + info.getName() + "' at " + t);
                        return t;
                    });
        } catch (IOException e) {
            LOG.log(Level.WARNING, "mDNS discovery failed", e);
            return Optional.empty();
        }
    }
}
