package orbit.host;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import orbit.protocol.Network;
import orbit.protocol.Protocol;

/** Announces the agent endpoint as {@value Protocol#MDNS_SERVICE_TYPE} so agents find the host without config. */
public final class MdnsAdvertiser implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(MdnsAdvertiser.class.getName());

    private JmDNS jmdns;

    public void start(String name, int port) {
        InetAddress address = Network.preferredLanAddress().orElse(null);
        try {
            jmdns = JmDNS.create(address, name);
            ServiceInfo info = ServiceInfo.create(Protocol.MDNS_SERVICE_TYPE, name, port, 0, 0,
                    Map.of("version", Integer.toString(Protocol.VERSION), "port", Integer.toString(port)));
            jmdns.registerService(info);
            String on = jmdns.getInetAddress().getHostAddress();
            LOG.info(() -> "advertising '" + name + "' via mDNS on " + on);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "mDNS advertising failed; agents must be configured with the host address", e);
        }
    }

    @Override
    public void close() {
        if (jmdns != null) {
            try {
                jmdns.unregisterAllServices();
                jmdns.close();
            } catch (IOException e) {
                LOG.log(Level.FINE, "closing mDNS", e);
            }
        }
    }
}
