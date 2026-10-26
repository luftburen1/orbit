package orbit.tray.sni;

import java.util.List;
import java.util.Map;
import org.freedesktop.dbus.Struct;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.Position;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

/** Desktop notifications. Spec: https://specifications.freedesktop.org/notification-spec/ */
@DBusInterfaceName("org.freedesktop.Notifications")
public interface Notifications extends DBusInterface {

    String BUS_NAME = "org.freedesktop.Notifications";
    String PATH = "/org/freedesktop/Notifications";

    UInt32 Notify(String appName, UInt32 replacesId, String appIcon, String summary, String body,
                  List<String> actions, Map<String, Variant<?>> hints, int expireTimeout);

    /** The {@code image-data} hint: RGBA pixels. D-Bus type {@code (iiibiiay)}. */
    final class ImageData extends Struct {
        public static final String SIGNATURE = "(iiibiiay)";

        @Position(0)
        public final int width;
        @Position(1)
        public final int height;
        @Position(2)
        public final int rowStride;
        @Position(3)
        public final boolean hasAlpha;
        @Position(4)
        public final int bitsPerSample;
        @Position(5)
        public final int channels;
        @Position(6)
        public final byte[] data;

        public ImageData(int width, int height, byte[] rgba) {
            this.width = width;
            this.height = height;
            this.rowStride = width * 4;
            this.hasAlpha = true;
            this.bitsPerSample = 8;
            this.channels = 4;
            this.data = rgba;
        }
    }
}
