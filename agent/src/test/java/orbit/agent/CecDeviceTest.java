package orbit.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

class CecDeviceTest {

    @Test
    void ioctlNumbersMatchTheKernelHeader() {
        // As linux/cec.h computes them (printed with gcc on x86-64; the same on arm64).
        assertEquals(0xc04c6100L, CecDevice.ADAP_G_CAPS);
        assertEquals(0x80026101L, CecDevice.ADAP_G_PHYS_ADDR);
        assertEquals(0x805c6103L, CecDevice.ADAP_G_LOG_ADDRS);
        assertEquals(0xc05c6104L, CecDevice.ADAP_S_LOG_ADDRS);
        assertEquals(0xc0386105L, CecDevice.TRANSMIT);
    }

    @Test
    void encodesAMessageWithItsAddressesAndReply() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment msg = arena.allocate(CecDevice.Msg.SIZE, 8);
            CecDevice.message(msg, 4, 0, HdmiCec.GIVE_DEVICE_POWER_STATUS, new byte[0], HdmiCec.REPORT_POWER_STATUS);
            assertEquals(2, msg.get(ValueLayout.JAVA_INT, CecDevice.Msg.LEN));
            assertEquals((byte) 0x40, msg.get(ValueLayout.JAVA_BYTE, CecDevice.Msg.MSG));
            assertEquals((byte) 0x8f, msg.get(ValueLayout.JAVA_BYTE, CecDevice.Msg.MSG + 1));
            assertEquals((byte) 0x90, msg.get(ValueLayout.JAVA_BYTE, CecDevice.Msg.REPLY));
            assertEquals(CecDevice.REPLY_TIMEOUT_MS, msg.get(ValueLayout.JAVA_INT, CecDevice.Msg.TIMEOUT));

            CecDevice.message(msg, 4, 15, HdmiCec.ACTIVE_SOURCE, new byte[] {0x10, 0x00}, 0);
            assertEquals(4, msg.get(ValueLayout.JAVA_INT, CecDevice.Msg.LEN));
            assertEquals((byte) 0x4f, msg.get(ValueLayout.JAVA_BYTE, CecDevice.Msg.MSG));
            assertEquals((byte) 0x10, msg.get(ValueLayout.JAVA_BYTE, CecDevice.Msg.MSG + 2));
            assertEquals(0, msg.get(ValueLayout.JAVA_BYTE, CecDevice.Msg.REPLY));
            assertEquals(0, msg.get(ValueLayout.JAVA_INT, CecDevice.Msg.TIMEOUT));
        }
    }

    @Test
    void readsTheReplyTheKernelPutInTheMessage() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment msg = arena.allocate(CecDevice.Msg.SIZE, 8);
            CecDevice.message(msg, 4, 0, HdmiCec.GIVE_DEVICE_POWER_STATUS, new byte[0], HdmiCec.REPORT_POWER_STATUS);
            // The TV's Report Power Status (from 0 to 4): standby.
            msg.set(ValueLayout.JAVA_INT, CecDevice.Msg.LEN, 3);
            msg.set(ValueLayout.JAVA_BYTE, CecDevice.Msg.MSG, (byte) 0x04);
            msg.set(ValueLayout.JAVA_BYTE, CecDevice.Msg.MSG + 1, (byte) 0x90);
            msg.set(ValueLayout.JAVA_BYTE, CecDevice.Msg.MSG + 2, (byte) 0x01);
            msg.set(ValueLayout.JAVA_BYTE, CecDevice.Msg.TX_STATUS, (byte) 0x01);
            msg.set(ValueLayout.JAVA_BYTE, CecDevice.Msg.RX_STATUS, (byte) 0x01);
            HdmiCec.Reply reply = CecDevice.reply(msg, HdmiCec.REPORT_POWER_STATUS);
            assertTrue(reply.sent());
            assertArrayEquals(new byte[] {1}, reply.operands());

            // Acknowledged, but the reply timed out.
            msg.set(ValueLayout.JAVA_BYTE, CecDevice.Msg.RX_STATUS, (byte) 0x02);
            reply = CecDevice.reply(msg, HdmiCec.REPORT_POWER_STATUS);
            assertTrue(reply.sent());
            assertNull(reply.operands());

            // Not acknowledged, and given up after the retries.
            msg.set(ValueLayout.JAVA_BYTE, CecDevice.Msg.TX_STATUS, (byte) (0x04 | 0x20));
            msg.set(ValueLayout.JAVA_BYTE, CecDevice.Msg.RX_STATUS, (byte) 0);
            assertFalse(CecDevice.reply(msg, HdmiCec.REPORT_POWER_STATUS).sent());
        }
    }

    @Test
    void claimsOnePlaybackDeviceThatPassesTheRemoteOn() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment addrs = arena.allocate(CecDevice.LogAddrs.SIZE, 8);
            CecDevice.playback(addrs, "Orbit");
            assertEquals(1, addrs.get(ValueLayout.JAVA_BYTE, CecDevice.LogAddrs.NUM_LOG_ADDRS));
            assertEquals(6, addrs.get(ValueLayout.JAVA_BYTE, CecDevice.LogAddrs.CEC_VERSION));
            assertEquals(2, addrs.get(ValueLayout.JAVA_INT, CecDevice.LogAddrs.FLAGS));
            assertEquals(-1, addrs.get(ValueLayout.JAVA_INT, CecDevice.LogAddrs.VENDOR_ID));
            assertEquals(4, addrs.get(ValueLayout.JAVA_BYTE, CecDevice.LogAddrs.PRIMARY_DEVICE_TYPE));
            assertEquals(3, addrs.get(ValueLayout.JAVA_BYTE, CecDevice.LogAddrs.LOG_ADDR_TYPE));
            assertEquals(0x10, addrs.get(ValueLayout.JAVA_BYTE, CecDevice.LogAddrs.ALL_DEVICE_TYPES));
            byte[] name = addrs.asSlice(CecDevice.LogAddrs.OSD_NAME, 6).toArray(ValueLayout.JAVA_BYTE);
            assertArrayEquals(new byte[] {'O', 'r', 'b', 'i', 't', 0}, name);
        }
    }

    @Test
    void readsTheClaimedLogicalAddress() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment addrs = arena.allocate(CecDevice.LogAddrs.SIZE, 8);
            addrs.set(ValueLayout.JAVA_BYTE, CecDevice.LogAddrs.LOG_ADDR, (byte) 0xff);
            assertEquals(-1, CecDevice.logicalAddress(addrs));
            addrs.set(ValueLayout.JAVA_BYTE, CecDevice.LogAddrs.LOG_ADDR, (byte) 4);
            addrs.set(ValueLayout.JAVA_SHORT, CecDevice.LogAddrs.LOG_ADDR_MASK, (short) 0x0010);
            assertEquals(4, CecDevice.logicalAddress(addrs));
        }
    }
}
