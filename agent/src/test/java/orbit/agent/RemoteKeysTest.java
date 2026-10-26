package orbit.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RemoteKeysTest {

    /** Part of /proc/bus/input/devices on a Raspberry Pi 5 with a keyboard plugged in. */
    private static final String DEVICES = """
            I: Bus=0003 Vendor=046d Product=c31c Version=0110
            N: Name="Logitech USB Keyboard"
            P: Phys=usb-xhci-hcd.0-1/input0
            H: Handlers=sysrq kbd leds event0
            B: EV=120013

            I: Bus=001e Vendor=0000 Product=0000 Version=0001
            N: Name="vc4-hdmi-0"
            P: Phys=vc4-hdmi-0/input0
            S: Sysfs=/devices/platform/axi/axi:gpu/rc/rc0/input3
            H: Handlers=kbd event3
            B: EV=100013

            I: Bus=001e Vendor=0000 Product=0000 Version=0001
            N: Name="vc4-hdmi-1"
            H: Handlers=kbd event4
            """;

    @Test
    void findsTheCecAdaptersInputDevice() {
        assertEquals(Optional.of(Path.of("/dev/input/event3")), RemoteKeys.inputDevice(DEVICES, "vc4-hdmi-0"));
        assertEquals(Optional.of(Path.of("/dev/input/event4")), RemoteKeys.inputDevice(DEVICES, "vc4-hdmi-1"));
        assertEquals(Optional.empty(), RemoteKeys.inputDevice(DEVICES, "vc4-hdmi"));
    }

    @Test
    void decodesPressesOfPlayPauseAndStop() {
        // What some TVs send for their Play/Pause and Stop buttons.
        assertEquals(Optional.of(RemoteKeys.Key.PLAY_PAUSE), pressed(event(1, 201, 1)));
        assertEquals(Optional.of(RemoteKeys.Key.STOP), pressed(event(1, 166, 1)));
        assertEquals(Optional.of(RemoteKeys.Key.PLAY_PAUSE), pressed(event(1, 164, 1)));
        assertEquals(Optional.of(RemoteKeys.Key.STOP), pressed(event(1, 512, 1)), "KEY_NUMERIC_0");
        assertEquals(Optional.of(RemoteKeys.Key.PLAY_PAUSE), pressed(event(1, 517, 1)), "KEY_NUMERIC_5");
        assertEquals(Optional.empty(), pressed(event(1, 513, 1)), "KEY_NUMERIC_1");
    }

    @Test
    void decodesTheArrowsAndOk() {
        // What such a TV sends.
        assertEquals(Optional.of(RemoteKeys.Key.UP), pressed(event(1, 103, 1)));
        assertEquals(Optional.of(RemoteKeys.Key.DOWN), pressed(event(1, 108, 1)));
        assertEquals(Optional.of(RemoteKeys.Key.OK), pressed(event(1, 352, 1)));
        assertEquals(Optional.of(RemoteKeys.Key.BACK), pressed(event(1, 174, 1)), "KEY_EXIT");
        assertEquals(Optional.of(RemoteKeys.Key.LEFT), pressed(event(1, 105, 1)));
        assertEquals(Optional.of(RemoteKeys.Key.RIGHT), pressed(event(1, 106, 1)));
    }

    @Test
    void ignoresReleasesRepeatsAndOtherKeys() {
        assertEquals(Optional.empty(), pressed(event(1, 201, 0)), "release");
        assertEquals(Optional.empty(), pressed(event(1, 201, 2)), "auto-repeat");
        assertEquals(Optional.empty(), pressed(event(1, 1, 1)), "KEY_ESC");
        assertEquals(Optional.empty(), pressed(event(0, 0, 0)), "EV_SYN");
    }

    private static byte[] event(int type, int code, int value) {
        ByteBuffer b = ByteBuffer.allocate(RemoteKeys.eventSize()).order(ByteOrder.nativeOrder());
        b.putLong(0, 1_700_000_000L);
        int at = b.capacity() - 8;
        b.putShort(at, (short) type).putShort(at + 2, (short) code).putInt(at + 4, value);
        return b.array();
    }

    private static Optional<RemoteKeys.Key> pressed(byte[] event) {
        return RemoteKeys.keyEvent(event).flatMap(RemoteKeys.Event::pressed);
    }
}
