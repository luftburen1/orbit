package orbit.agent;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;

/**
 * A mouse or keyboard made up through Linux's {@code /dev/uinput}: to other programs, Moonlight included, it looks like
 * one plugged in, so what it does reaches the host through the stream.
 */
final class VirtualInput implements AutoCloseable {

    // input-event-codes.h
    static final int EV_SYN = 0;
    static final int EV_KEY = 1;
    static final int EV_REL = 2;
    private static final int SYN_REPORT = 0;
    private static final int REL_X = 0;
    private static final int REL_Y = 1;
    static final int BTN_LEFT = 0x110;
    static final int BTN_RIGHT = 0x111;
    private static final int BUS_VIRTUAL = 0x06;

    // uinput.h
    private static final long UI_DEV_CREATE = 0x5501;
    private static final long UI_DEV_DESTROY = 0x5502;
    /** {@code struct uinput_setup}: {@code struct input_id} (4 × u16), {@code char name[80]}, u32 ff_effects_max. */
    static final int SETUP_SIZE = 8 + 80 + 4;
    static final long UI_DEV_SETUP = Libc.iow('U', 3, SETUP_SIZE);
    static final long UI_SET_EVBIT = Libc.iow('U', 100, 4);
    private static final long UI_SET_KEYBIT = Libc.iow('U', 101, 4);
    private static final long UI_SET_RELBIT = Libc.iow('U', 102, 4);

    private final int fd;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment buffer;
    private final int eventSize = RemoteKeys.eventSize();

    private VirtualInput(int fd) {
        this.fd = fd;
        this.buffer = arena.allocate(eventSize * 3L);
    }

    /** A two-button mouse. */
    static VirtualInput mouse() throws IOException {
        return create("Orbit remote mouse", 1, new int[] {BTN_LEFT, BTN_RIGHT}, true);
    }

    /**
     * A keyboard with every key a remote could send: all key codes but the button ranges, which would make it look
     * like a mouse or a game controller.
     */
    static VirtualInput keyboard() throws IOException {
        return create("Orbit remote keys", 2, keyboardKeys(), false);
    }

    static int[] keyboardKeys() {
        return IntStream.rangeClosed(1, 0x2bf)
                .filter(k -> k < 0x100 || k >= 0x160)
                .filter(k -> k < 0x220 || k > 0x223) // BTN_DPAD_*
                .toArray();
    }

    /** Fails when {@code /dev/uinput} is missing or this user may not write it. */
    private static VirtualInput create(String name, int product, int[] keys, boolean pointer) throws IOException {
        int fd = Libc.open("/dev/uinput", Libc.O_WRONLY | Libc.O_NONBLOCK);
        if (fd < 0) {
            throw new IOException("cannot open /dev/uinput (missing, or this user may not write it)");
        }
        VirtualInput device = new VirtualInput(fd);
        try {
            device.ioctl(UI_SET_EVBIT, EV_KEY);
            for (int key : keys) {
                device.ioctl(UI_SET_KEYBIT, key);
            }
            if (pointer) {
                device.ioctl(UI_SET_EVBIT, EV_REL);
                device.ioctl(UI_SET_RELBIT, REL_X);
                device.ioctl(UI_SET_RELBIT, REL_Y);
            }
            try (Arena a = Arena.ofConfined()) {
                MemorySegment setup = a.allocate(SETUP_SIZE);
                setup.copyFrom(MemorySegment.ofArray(setup(name, product)));
                if (!Libc.ioctl(fd, UI_DEV_SETUP, setup)) {
                    throw new IOException("uinput refused the setup of " + name);
                }
            }
            device.ioctl(UI_DEV_CREATE, 0);
            return device;
        } catch (IOException | RuntimeException e) {
            device.close();
            throw e;
        }
    }

    /** The {@code struct uinput_setup} bytes. */
    static byte[] setup(String name, int product) {
        ByteBuffer b = ByteBuffer.allocate(SETUP_SIZE).order(ByteOrder.nativeOrder());
        b.putShort((short) BUS_VIRTUAL).putShort((short) 0x4f52).putShort((short) product).putShort((short) 1);
        byte[] bytes = name.getBytes(StandardCharsets.US_ASCII);
        b.put(bytes, 0, Math.min(bytes.length, 79));
        return b.array();
    }

    private void ioctl(long request, long arg) throws IOException {
        if (!Libc.ioctl(fd, request, arg)) {
            throw new IOException("uinput ioctl 0x" + Long.toHexString(request) + " failed");
        }
    }

    /** Moves the pointer by this many pixels (before the receiver's acceleration). */
    synchronized void move(int dx, int dy) throws IOException {
        int n = 0;
        if (dx != 0) {
            put(n++, EV_REL, REL_X, dx);
        }
        if (dy != 0) {
            put(n++, EV_REL, REL_Y, dy);
        }
        put(n++, EV_SYN, SYN_REPORT, 0);
        Libc.writeFully(fd, buffer, (long) n * eventSize);
    }

    /** A key or button: 1 pressed, 0 released, 2 held (auto-repeat). */
    synchronized void key(int code, int value) throws IOException {
        put(0, EV_KEY, code, value);
        put(1, EV_SYN, SYN_REPORT, 0);
        Libc.writeFully(fd, buffer, 2L * eventSize);
    }

    /** One {@code struct input_event} with a zero timestamp (the kernel stamps it). */
    private void put(int index, int type, int code, int value) {
        long at = (long) index * eventSize;
        buffer.asSlice(at, eventSize).fill((byte) 0);
        long tail = at + eventSize - 8;
        buffer.set(ValueLayout.JAVA_SHORT_UNALIGNED, tail, (short) type);
        buffer.set(ValueLayout.JAVA_SHORT_UNALIGNED, tail + 2, (short) code);
        buffer.set(ValueLayout.JAVA_INT_UNALIGNED, tail + 4, value);
    }

    @Override
    public synchronized void close() {
        Libc.ioctl(fd, UI_DEV_DESTROY, 0);
        Libc.close(fd);
        arena.close();
    }
}
