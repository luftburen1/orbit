package orbit.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import orbit.protocol.Protocol;
import org.junit.jupiter.api.Test;

class HostLocatorTest {

    @Test
    void parsesAddresses() {
        assertEquals(new HostLocator.Target("10.0.0.2", Protocol.DEFAULT_PORT), HostLocator.parse("10.0.0.2"));
        assertEquals(new HostLocator.Target("gamer.lan", 1234), HostLocator.parse(" gamer.lan:1234 "));
        assertEquals(new HostLocator.Target("fe80::1", Protocol.DEFAULT_PORT), HostLocator.parse("[fe80::1]"));
        assertEquals(new HostLocator.Target("fe80::1", 99), HostLocator.parse("[fe80::1]:99"));
    }

    @Test
    void configuredHostSkipsDiscovery() {
        assertEquals(new HostLocator.Target("h", 5), new HostLocator("h:5", null).locate().orElseThrow());
    }
}
