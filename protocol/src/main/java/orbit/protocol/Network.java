package orbit.protocol;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Comparator;
import java.util.Optional;

/** Picks the LAN address used for mDNS, since {@code InetAddress.getLocalHost()} is often a loopback on Linux. */
public final class Network {

    private Network() {
    }

    /** First IPv4 address of an up, non-loopback, non-virtual interface, preferring private (site-local) ones. */
    public static Optional<InetAddress> preferredLanAddress() {
        try {
            return Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                    .filter(Network::usable)
                    .flatMap(NetworkInterface::inetAddresses)
                    .filter(a -> a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress())
                    .min(Comparator.comparing(a -> a.isSiteLocalAddress() ? 0 : 1));
        } catch (SocketException e) {
            return Optional.empty();
        }
    }

    private static boolean usable(NetworkInterface nif) {
        try {
            return nif.isUp() && !nif.isLoopback() && !nif.isVirtual() && !nif.isPointToPoint();
        } catch (SocketException e) {
            return false;
        }
    }
}
