package orbit.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NetworkTest {

    @Test
    void prefersTheNetworkCardOverADockerBridgeListedFirst() {
        Optional<InetAddress> chosen = Network.choose(List.of(
                new Network.Candidate("br-b64efb3bce36", false, List.of(ip("172.19.0.1"))),
                new Network.Candidate("enp3s0", true, List.of(ip("fe80::1"), ip("192.168.0.2")))));

        assertEquals(Optional.of(ip("192.168.0.2")), chosen);
    }

    @Test
    void prefersPrivateAddressesAmongNetworkCards() {
        Optional<InetAddress> chosen = Network.choose(List.of(
                new Network.Candidate("eth0", true, List.of(ip("203.0.113.5"))),
                new Network.Candidate("eth1", true, List.of(ip("10.0.0.7")))));

        assertEquals(Optional.of(ip("10.0.0.7")), chosen);
    }

    @Test
    void fallsBackToABridgeWithoutANetworkCard() {
        Optional<InetAddress> chosen = Network.choose(List.of(
                new Network.Candidate("br0", false, List.of(ip("192.168.1.10")))));

        assertEquals(Optional.of(ip("192.168.1.10")), chosen);
    }

    @Test
    void skipsIpv6LoopbackAndLinkLocal() {
        assertTrue(Network.choose(List.of(
                new Network.Candidate("eth0", true, List.of(ip("fe80::1"), ip("169.254.3.4"), ip("127.0.0.2")))))
                .isEmpty());
    }

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new AssertionError(e);
        }
    }
}
