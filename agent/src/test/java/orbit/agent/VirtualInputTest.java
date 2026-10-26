package orbit.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class VirtualInputTest {

    @Test
    void ioctlNumbersMatchUinputH() {
        assertEquals(0x405c5503L, VirtualInput.UI_DEV_SETUP);
        assertEquals(0x40045564L, VirtualInput.UI_SET_EVBIT);
    }

    @Test
    void setupHoldsAVirtualBusAndANulTerminatedName() {
        byte[] setup = VirtualInput.setup("Orbit remote mouse", 1);
        assertEquals(92, setup.length);
        assertEquals(0x06, ByteBuffer.wrap(setup).order(ByteOrder.nativeOrder()).getShort(0));
        assertEquals("Orbit remote mouse", new String(setup, 8, 18, StandardCharsets.US_ASCII));
        assertEquals(0, setup[8 + 18]);
    }

    @Test
    void keyboardHasTheRemotesKeysButNoButtons() {
        int[] keys = VirtualInput.keyboardKeys();
        for (int key : new int[] {28, 103, 108, 105, 106, 352, 398}) {
            assertTrue(Arrays.stream(keys).anyMatch(k -> k == key), "key " + key);
        }
        assertFalse(Arrays.stream(keys).anyMatch(k -> k >= 0x100 && k < 0x160), "mouse and controller buttons");
    }
}
