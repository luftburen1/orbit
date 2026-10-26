package orbit.agent;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A {@code /dev/cec*} adapter through the kernel's CEC API ({@code linux/cec.h}), with {@link Libc}'s ioctls. The
 * file is opened in blocking mode, so a transmit returns once it's on the bus, or once the reply asked for came (or
 * didn't). The logical address claimed here stays with the adapter after the file closes, as with {@code cec-ctl}.
 */
final class CecDevice implements HdmiCec.Port {

    // ioctls, from linux/cec.h; CecDeviceTest checks them against the header's values.
    static final long ADAP_G_CAPS = Libc.iowr('a', 0, Caps.SIZE);
    static final long ADAP_G_PHYS_ADDR = Libc.ior('a', 1, 2);
    static final long ADAP_G_LOG_ADDRS = Libc.ior('a', 3, LogAddrs.SIZE);
    static final long ADAP_S_LOG_ADDRS = Libc.iowr('a', 4, LogAddrs.SIZE);
    static final long TRANSMIT = Libc.iowr('a', 5, Msg.SIZE);

    /** {@code struct cec_caps}. */
    static final class Caps {
        static final int SIZE = 76;
        static final int NAME = 32;
        static final int NAME_LENGTH = 32;
    }

    /** {@code struct cec_log_addrs}. */
    static final class LogAddrs {
        static final int SIZE = 92;
        static final int LOG_ADDR = 0;
        static final int LOG_ADDR_MASK = 4;
        static final int CEC_VERSION = 6;
        static final int NUM_LOG_ADDRS = 7;
        static final int VENDOR_ID = 8;
        static final int FLAGS = 12;
        static final int OSD_NAME = 16;
        static final int OSD_NAME_LENGTH = 15;
        static final int PRIMARY_DEVICE_TYPE = 31;
        static final int LOG_ADDR_TYPE = 35;
        static final int ALL_DEVICE_TYPES = 39;

        static final int CEC_VERSION_2_0 = 6;
        static final int VENDOR_ID_NONE = 0xffffffff;
        /** The TV's remote reaches the kernel's input device for this adapter, which {@link RemoteKeys} reads. */
        static final int FL_ALLOW_RC_PASSTHRU = 1 << 1;
        static final int PRIM_DEVTYPE_PLAYBACK = 4;
        static final int LOG_ADDR_TYPE_PLAYBACK = 3;
        static final int ALL_DEVTYPE_PLAYBACK = 0x10;
    }

    /** {@code struct cec_msg}. */
    static final class Msg {
        static final int SIZE = 56;
        static final int LEN = 16;
        static final int TIMEOUT = 20;
        static final int MSG = 32;
        static final int MAX_LEN = 16;
        static final int REPLY = 48;
        static final int RX_STATUS = 49;
        static final int TX_STATUS = 50;

        static final int TX_STATUS_OK = 1;
        static final int RX_STATUS_OK = 1;
    }

    /** How long a transmit waits for the reply it asks for. */
    static final int REPLY_TIMEOUT_MS = 1000;
    private static final int INVALID_LOG_ADDR = 0xff;

    private final String device;
    private final int fd;

    private CecDevice(String device, int fd) {
        this.device = device;
        this.fd = fd;
    }

    /** Opens {@code device} for reading and sending. */
    static CecDevice open(String device) throws IOException {
        int fd = Libc.open(device, Libc.O_RDWR);
        if (fd < 0) {
            Path path = Path.of(device);
            boolean denied = Files.exists(path) && !(Files.isReadable(path) && Files.isWritable(path));
            throw new IOException("cannot open " + device + (denied ? " (add this user to the video group)" : ""));
        }
        return new CecDevice(device, fd);
    }

    @Override
    public String adapterName() throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment caps = arena.allocate(Caps.SIZE, 8);
            ioctl(ADAP_G_CAPS, caps, "read the adapter's capabilities");
            return cString(caps, Caps.NAME, Caps.NAME_LENGTH);
        }
    }

    @Override
    public int physicalAddress() throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment addr = arena.allocate(2, 2);
            ioctl(ADAP_G_PHYS_ADDR, addr, "read the physical address");
            return Short.toUnsignedInt(addr.get(ValueLayout.JAVA_SHORT, 0));
        }
    }

    @Override
    public int logicalAddress() throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment addrs = arena.allocate(LogAddrs.SIZE, 8);
            ioctl(ADAP_G_LOG_ADDRS, addrs, "read the logical addresses");
            return logicalAddress(addrs);
        }
    }

    /** The first logical address claimed, or -1 while none is. */
    static int logicalAddress(MemorySegment addrs) {
        int mask = Short.toUnsignedInt(addrs.get(ValueLayout.JAVA_SHORT, LogAddrs.LOG_ADDR_MASK));
        int first = Byte.toUnsignedInt(addrs.get(ValueLayout.JAVA_BYTE, LogAddrs.LOG_ADDR));
        return mask == 0 || first == INVALID_LOG_ADDR ? -1 : first;
    }

    @Override
    public void claimPlayback(String osdName) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment addrs = arena.allocate(LogAddrs.SIZE, 8);
            playback(addrs, osdName);
            ioctl(ADAP_S_LOG_ADDRS, addrs, "claim a logical address");
        }
    }

    /** One playback device named {@code osdName}, passing the TV's remote on, as {@code cec-ctl --playback} sets. */
    static void playback(MemorySegment addrs, String osdName) {
        addrs.fill((byte) 0);
        addrs.set(ValueLayout.JAVA_BYTE, LogAddrs.CEC_VERSION, (byte) LogAddrs.CEC_VERSION_2_0);
        addrs.set(ValueLayout.JAVA_BYTE, LogAddrs.NUM_LOG_ADDRS, (byte) 1);
        addrs.set(ValueLayout.JAVA_INT, LogAddrs.VENDOR_ID, LogAddrs.VENDOR_ID_NONE);
        addrs.set(ValueLayout.JAVA_INT, LogAddrs.FLAGS, LogAddrs.FL_ALLOW_RC_PASSTHRU);
        byte[] name = osdName.getBytes(StandardCharsets.US_ASCII);
        MemorySegment.copy(name, 0, addrs, ValueLayout.JAVA_BYTE, LogAddrs.OSD_NAME,
                Math.min(name.length, LogAddrs.OSD_NAME_LENGTH - 1));
        addrs.set(ValueLayout.JAVA_BYTE, LogAddrs.PRIMARY_DEVICE_TYPE, (byte) LogAddrs.PRIM_DEVTYPE_PLAYBACK);
        addrs.set(ValueLayout.JAVA_BYTE, LogAddrs.LOG_ADDR_TYPE, (byte) LogAddrs.LOG_ADDR_TYPE_PLAYBACK);
        addrs.set(ValueLayout.JAVA_BYTE, LogAddrs.ALL_DEVICE_TYPES, (byte) LogAddrs.ALL_DEVTYPE_PLAYBACK);
    }

    @Override
    public HdmiCec.Reply transmit(int destination, int opcode, byte[] operands, int replyOpcode)
            throws IOException {
        int initiator = logicalAddress();
        if (initiator < 0) {
            return new HdmiCec.Reply(false, null);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment msg = arena.allocate(Msg.SIZE, 8);
            message(msg, initiator, destination, opcode, operands, replyOpcode);
            // Fails when the adapter lost its address meanwhile, e.g. the TV was unplugged: not sent then.
            if (!Libc.ioctl(fd, TRANSMIT, msg)) {
                return new HdmiCec.Reply(false, null);
            }
            return reply(msg, replyOpcode);
        }
    }

    /** A {@code struct cec_msg} to send; {@code replyOpcode} 0 to wait for no reply. */
    static void message(MemorySegment msg, int initiator, int destination, int opcode, byte[] operands,
            int replyOpcode) {
        if (operands.length + 2 > Msg.MAX_LEN) {
            throw new IllegalArgumentException("a CEC message has at most " + Msg.MAX_LEN + " bytes");
        }
        msg.fill((byte) 0);
        msg.set(ValueLayout.JAVA_INT, Msg.LEN, operands.length + 2);
        msg.set(ValueLayout.JAVA_BYTE, Msg.MSG, (byte) ((initiator << 4) | (destination & 0x0f)));
        msg.set(ValueLayout.JAVA_BYTE, Msg.MSG + 1, (byte) opcode);
        MemorySegment.copy(operands, 0, msg, ValueLayout.JAVA_BYTE, Msg.MSG + 2, operands.length);
        if (replyOpcode != 0) {
            msg.set(ValueLayout.JAVA_BYTE, Msg.REPLY, (byte) replyOpcode);
            msg.set(ValueLayout.JAVA_INT, Msg.TIMEOUT, REPLY_TIMEOUT_MS);
        }
    }

    /**
     * What came of a transmit: sent (for a message to one device, that it acknowledged), and the reply's operands
     * when one was asked for and came. The kernel puts the reply in the same {@code cec_msg}.
     */
    static HdmiCec.Reply reply(MemorySegment msg, int replyOpcode) {
        boolean sent = (msg.get(ValueLayout.JAVA_BYTE, Msg.TX_STATUS) & Msg.TX_STATUS_OK) != 0;
        if (!sent || replyOpcode == 0
                || (msg.get(ValueLayout.JAVA_BYTE, Msg.RX_STATUS) & Msg.RX_STATUS_OK) == 0) {
            return new HdmiCec.Reply(sent, null);
        }
        int len = msg.get(ValueLayout.JAVA_INT, Msg.LEN);
        if (len < 2 || len > Msg.MAX_LEN
                || Byte.toUnsignedInt(msg.get(ValueLayout.JAVA_BYTE, Msg.MSG + 1)) != replyOpcode) {
            return new HdmiCec.Reply(true, null);
        }
        return new HdmiCec.Reply(true, msg.asSlice(Msg.MSG + 2, len - 2).toArray(ValueLayout.JAVA_BYTE));
    }

    @Override
    public void close() {
        Libc.close(fd);
    }

    private void ioctl(long request, MemorySegment arg, String what) throws IOException {
        if (!Libc.ioctl(fd, request, arg)) {
            throw new IOException("cannot " + what + " of " + device);
        }
    }

    private static String cString(MemorySegment segment, int offset, int length) {
        byte[] bytes = segment.asSlice(offset, length).toArray(ValueLayout.JAVA_BYTE);
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, 0, end, StandardCharsets.US_ASCII);
    }
}
