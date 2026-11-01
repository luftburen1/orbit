package orbit.protocol;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Picks the LAN address used for mDNS, since {@code InetAddress.getLocalHost()} is often a loopback on Linux. */
public final class Network {

    private static final Path SYS_NET = Path.of("/sys/class/net");

    private Network() {
    }

    /** An up interface that could carry mDNS: its name, whether it's backed by hardware, and its addresses. */
    record Candidate(String name, boolean physical, List<InetAddress> addresses) {
    }

    /**
     * First IPv4 address of an up, non-loopback, non-virtual interface, preferring interfaces backed by hardware, then
     * private (site-local) addresses. Java doesn't count bridges (Docker's, libvirt's) as virtual, and they have
     * private addresses too.
     */
    public static Optional<InetAddress> preferredLanAddress() {
        try {
            return choose(Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                    .filter(Network::usable)
                    .map(nif -> new Candidate(nif.getName(), physical(nif.getName()),
                            Collections.list(nif.getInetAddresses())))
                    .toList());
        } catch (SocketException e) {
            return Optional.empty();
        }
    }

    static Optional<InetAddress> choose(List<Candidate> candidates) {
        record Choice(boolean physical, InetAddress address) {
        }
        return candidates.stream()
                .flatMap(c -> c.addresses().stream().map(a -> new Choice(c.physical(), a)))
                .filter(c -> c.address() instanceof Inet4Address
                        && !c.address().isLoopbackAddress() && !c.address().isLinkLocalAddress())
                .min(Comparator.comparing((Choice c) -> c.physical() ? 0 : 1)
                        .thenComparing(c -> c.address().isSiteLocalAddress() ? 0 : 1))
                .map(Choice::address);
    }

    /** On Linux, a network card has a {@code device} link in sysfs; bridges, veths and tunnels don't. */
    private static boolean physical(String name) {
        if (!Files.isDirectory(SYS_NET)) {
            return true; // can't tell on this OS: rank all alike
        }
        return Files.exists(SYS_NET.resolve(name).resolve("device"));
    }

    private static boolean usable(NetworkInterface nif) {
        try {
            return nif.isUp() && !nif.isLoopback() && !nif.isVirtual() && !nif.isPointToPoint();
        } catch (SocketException e) {
            return false;
        }
    }
}
