package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Rectangle;
import java.util.List;
import org.junit.jupiter.api.Test;

class StopQuestionTest {

    @Test
    void theQuestionGoesOnTheScreenDeviceStartingThere() {
        List<Rectangle> devices = List.of(new Rectangle(0, 0, 3440, 1440), new Rectangle(3440, 0, 3840, 2160));
        assertEquals(new Rectangle(3440, 0, 3840, 2160),
                StopQuestion.deviceBounds(new Rectangle(3440, 0, 2560, 1440), devices));
        Rectangle desktop = new Rectangle(3440, 0, 3840, 2160);
        assertEquals(desktop, StopQuestion.deviceBounds(desktop, List.of()), "no devices");
    }
}
