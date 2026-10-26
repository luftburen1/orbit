package orbit.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HdmiCecTest {

    /** What went out on the bus. */
    private record Sent(int destination, int opcode, List<Byte> operands) {
    }

    /** A stand-in for the kernel's CEC adapter and a TV on it: answers like them, and records what's sent. */
    private static final class FakeTv implements HdmiCec.Opener, HdmiCec.Port {
        final List<Sent> sent = new ArrayList<>();
        final List<String> claims = new ArrayList<>();
        int opened;
        int closed;
        int physAddr = 0x1000;
        int logicalAddress = 4;
        boolean answers = true;
        /** Whether the TV reports its power status at all. */
        boolean reportsPower = true;
        int power = 1; // standby
        /** Power status queries after Image View On before the TV says it's on. */
        int wakeQueries = 2;
        boolean woken;

        @Override
        public HdmiCec.Port open(String device) {
            opened++;
            return this;
        }

        @Override
        public String adapterName() {
            return "vc4-hdmi-0";
        }

        @Override
        public int physicalAddress() {
            return physAddr;
        }

        @Override
        public int logicalAddress() {
            return logicalAddress;
        }

        @Override
        public void claimPlayback(String osdName) {
            claims.add(osdName);
            logicalAddress = 4;
        }

        @Override
        public HdmiCec.Reply transmit(int destination, int opcode, byte[] operands, int replyOpcode) {
            if (logicalAddress < 0) {
                return new HdmiCec.Reply(false, null);
            }
            List<Byte> bytes = new ArrayList<>();
            for (byte b : operands) {
                bytes.add(b);
            }
            sent.add(new Sent(destination, opcode, bytes));
            if (destination == HdmiCec.BROADCAST) {
                return new HdmiCec.Reply(true, null);
            }
            if (!answers) {
                return new HdmiCec.Reply(false, null);
            }
            if (opcode == HdmiCec.IMAGE_VIEW_ON) {
                woken = true;
                return new HdmiCec.Reply(true, null);
            }
            if (opcode == HdmiCec.GIVE_DEVICE_POWER_STATUS) {
                if (!reportsPower) {
                    return new HdmiCec.Reply(true, null);
                }
                if (woken && power != 0 && wakeQueries-- <= 0) {
                    power = 0;
                }
                return new HdmiCec.Reply(true, new byte[] {(byte) (woken && power != 0 ? 2 : power)});
            }
            return new HdmiCec.Reply(true, null);
        }

        @Override
        public void close() {
            closed++;
        }

        long count(int opcode) {
            return sent.stream().filter(s -> s.opcode() == opcode).count();
        }

        int indexOf(int opcode) {
            for (int i = 0; i < sent.size(); i++) {
                if (sent.get(i).opcode() == opcode) {
                    return i;
                }
            }
            return -1;
        }
    }

    @TempDir
    Path dev;

    private HdmiCec cec(FakeTv tv) throws IOException {
        Files.createFile(dev.resolve("cec0"));
        HdmiCec cec = new HdmiCec(tv, dev, null);
        cec.pollInterval = Duration.ofMillis(10);
        cec.wakeTimeout = Duration.ofSeconds(2);
        return cec;
    }

    @Test
    void readsPowerStatusReplies() throws IOException {
        FakeTv tv = new FakeTv();
        assertEquals(HdmiCec.Power.STANDBY, HdmiCec.powerStatus(tv));
        tv.power = 0;
        assertEquals(HdmiCec.Power.ON, HdmiCec.powerStatus(tv));
        tv.reportsPower = false;
        assertEquals(HdmiCec.Power.UNKNOWN, HdmiCec.powerStatus(tv));
        tv.answers = false;
        assertEquals(HdmiCec.Power.NO_ANSWER, HdmiCec.powerStatus(tv));
    }

    @Test
    void formatsPhysicalAddresses() {
        assertEquals("1.0.0.0", HdmiCec.physAddrText(0x1000));
        assertEquals("2.1.0.0", HdmiCec.physAddrText(0x2100));
        assertEquals("15.15.15.15", HdmiCec.physAddrText(HdmiCec.NO_PHYS_ADDR));
    }

    @Test
    void listsCecDevicesInOrder() throws IOException {
        Files.createFile(dev.resolve("cec1"));
        Files.createFile(dev.resolve("cec0"));
        Files.createFile(dev.resolve("fb0"));
        assertEquals(List.of(dev.resolve("cec0").toString(), dev.resolve("cec1").toString()),
                HdmiCec.devices(dev, null));
        assertEquals(List.of(), HdmiCec.devices(dev, dev.resolve("cec7").toString()));
    }

    @Test
    void wakesTvWaitsUntilItsOnThenSwitchesInput() throws Exception {
        FakeTv tv = new FakeTv();
        assertTrue(cec(tv).switchTvToThisInput());
        assertEquals(0, tv.power);
        assertEquals(new Sent(HdmiCec.BROADCAST, HdmiCec.ACTIVE_SOURCE, List.of((byte) 0x10, (byte) 0x00)),
                tv.sent.getLast());
        // Image View On, then polled until the TV said it's on (twice "to on", then "on"), then Active Source.
        assertEquals(1, tv.count(HdmiCec.IMAGE_VIEW_ON));
        assertEquals(HdmiCec.TV, tv.sent.get(tv.indexOf(HdmiCec.IMAGE_VIEW_ON)).destination());
        assertTrue(tv.indexOf(HdmiCec.IMAGE_VIEW_ON) < tv.indexOf(HdmiCec.ACTIVE_SOURCE));
        assertEquals(1 + 3, tv.count(HdmiCec.GIVE_DEVICE_POWER_STATUS));
        assertEquals(tv.opened, tv.closed);
    }

    @Test
    void tvAlreadyOnIsSwitchedWithoutWaiting() throws Exception {
        FakeTv tv = new FakeTv();
        tv.power = 0;
        assertTrue(cec(tv).switchTvToThisInput());
        assertEquals(1, tv.count(HdmiCec.GIVE_DEVICE_POWER_STATUS));
        assertEquals(1, tv.count(HdmiCec.IMAGE_VIEW_ON));
        assertEquals(1, tv.count(HdmiCec.ACTIVE_SOURCE));
    }

    @Test
    void tvThatDoesntReportPowerIsSwitchedWithoutWaiting() throws Exception {
        FakeTv tv = new FakeTv();
        tv.reportsPower = false;
        assertTrue(cec(tv).switchTvToThisInput());
        assertEquals(1, tv.count(HdmiCec.GIVE_DEVICE_POWER_STATUS));
        assertEquals(1, tv.count(HdmiCec.ACTIVE_SOURCE));
    }

    @Test
    void claimsAnAddressWhenTheAdapterHasNone() throws Exception {
        FakeTv tv = new FakeTv();
        tv.logicalAddress = -1;
        tv.power = 0;
        assertTrue(cec(tv).switchTvToThisInput());
        assertEquals(List.of("Orbit"), tv.claims);
    }

    @Test
    void leavesAnAddressSomeoneElseClaimed() throws Exception {
        FakeTv tv = new FakeTv();
        tv.power = 0;
        assertTrue(cec(tv).switchTvToThisInput());
        assertEquals(List.of(), tv.claims);
    }

    @Test
    void doesNothingWithoutAnsweringTv() throws Exception {
        FakeTv tv = new FakeTv();
        tv.answers = false;
        assertFalse(cec(tv).switchTvToThisInput());
        assertEquals(0, tv.count(HdmiCec.IMAGE_VIEW_ON));
        assertEquals(0, tv.count(HdmiCec.ACTIVE_SOURCE));
    }

    @Test
    void doesNothingWithoutDisplay() throws Exception {
        FakeTv tv = new FakeTv();
        tv.physAddr = HdmiCec.NO_PHYS_ADDR;
        assertFalse(cec(tv).switchTvToThisInput());
        assertEquals(List.of(), tv.sent);
        assertEquals(List.of(), tv.claims);
    }

    @Test
    void goesAheadWhenTheTvNeverSaysItsOn() throws Exception {
        FakeTv tv = new FakeTv();
        tv.wakeQueries = Integer.MAX_VALUE;
        HdmiCec cec = cec(tv);
        cec.wakeTimeout = Duration.ofMillis(200);
        // Active Source still goes out: the TV may switch anyway.
        assertTrue(cec.switchTvToThisInput());
        assertEquals(1, tv.count(HdmiCec.ACTIVE_SOURCE));
    }

    @Test
    void adapterNamesIncludeAdaptersWithoutDisplay() throws Exception {
        FakeTv tv = new FakeTv();
        tv.physAddr = HdmiCec.NO_PHYS_ADDR;
        assertEquals(List.of("vc4-hdmi-0"), cec(tv).adapterNames());
    }

    @Test
    void anAdapterThatCantBeOpenedIsSkipped() throws Exception {
        Files.createFile(dev.resolve("cec0"));
        HdmiCec cec = new HdmiCec(device -> {
            throw new IOException("cannot open " + device);
        }, dev, null);
        assertFalse(cec.switchTvToThisInput());
        assertEquals(List.of(), cec.adapterNames());
    }
}
