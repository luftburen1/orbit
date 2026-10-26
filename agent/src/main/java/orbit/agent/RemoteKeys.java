package orbit.agent;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the TV remote's media keys, arrows, OK, 5 (as Play/Pause) and 0 (as Stop). With remote-control passthrough
 * (which {@link CecDevice#playback} turns on), the TV sends its remote's keys to the device it shows, and the kernel
 * turns them into key presses on an input device named after the CEC adapter, e.g. {@code vc4-hdmi-0}. During a
 * stream the agent can {@link #grab} it, so the keys reach Moonlight only the way {@link StreamRemote} passes them on.
 */
final class RemoteKeys {

    private static final Logger LOG = Logger.getLogger(RemoteKeys.class.getName());

    enum Key { PLAY_PAUSE, STOP, UP, DOWN, LEFT, RIGHT, OK, BACK }

    private static final int EV_KEY = 1;
    private static final int PRESSED = 1;
    /** Linux key codes (input-event-codes.h) a TV remote's buttons arrive as. */
    static final Map<Integer, Key> KEYS = Map.ofEntries(
            Map.entry(164, Key.PLAY_PAUSE), // KEY_PLAYPAUSE
            Map.entry(200, Key.PLAY_PAUSE), // KEY_PLAYCD
            Map.entry(201, Key.PLAY_PAUSE), // KEY_PAUSECD
            Map.entry(207, Key.PLAY_PAUSE), // KEY_PLAY
            // For remotes without Play/Pause, or whose TV doesn't pass it on, as with 0 for Stop below.
            Map.entry(517, Key.PLAY_PAUSE), // KEY_NUMERIC_5
            Map.entry(128, Key.STOP), // KEY_STOP
            Map.entry(166, Key.STOP), // KEY_STOPCD
            // For remotes without Stop, or whose TV doesn't pass it on: 0, which the kernel's CEC keymap sends as
            // KEY_NUMERIC_0 and nothing in a stream understands.
            Map.entry(512, Key.STOP), // KEY_NUMERIC_0
            Map.entry(103, Key.UP), // KEY_UP
            Map.entry(108, Key.DOWN), // KEY_DOWN
            Map.entry(105, Key.LEFT), // KEY_LEFT
            Map.entry(106, Key.RIGHT), // KEY_RIGHT
            Map.entry(352, Key.OK), // KEY_OK
            Map.entry(353, Key.OK), // KEY_SELECT
            Map.entry(28, Key.OK), // KEY_ENTER
            Map.entry(174, Key.BACK), // KEY_EXIT
            Map.entry(158, Key.BACK)); // KEY_BACK

    private static final Pattern NAME = Pattern.compile("^N: Name=\"(.*)\"$", Pattern.MULTILINE);
    private static final Pattern EVENT = Pattern.compile("^H: Handlers=.*\\b(event\\d+)\\b", Pattern.MULTILINE);

    /** One key event: {@code value} 1 pressed, 0 released, 2 held (auto-repeat). */
    record Event(int code, int value) {
        /** The key a press stands for; empty for releases, repeats and other keys. */
        Optional<Key> pressed() {
            return value == PRESSED ? Optional.ofNullable(KEYS.get(code)) : Optional.empty();
        }
    }

    /** EVIOCGRAB: {@code _IOW('E', 0x90, int)}. */
    private static final long EVIOCGRAB = Libc.iow('E', 0x90, 4);

    /** Open file descriptors of the devices being read; a grab applies to these. */
    private final List<Integer> fds = new CopyOnWriteArrayList<>();
    /** The ones grabbed now: grabbing one twice fails (EBUSY), even from the same process. */
    private final Set<Integer> grabbed = ConcurrentHashMap.newKeySet();

    private RemoteKeys() {
    }

    /**
     * Listens on the input devices of these CEC adapters, each on its own daemon thread, and passes their key events
     * to {@code onEvent} (on that thread). Logs why when there's nothing to listen on.
     */
    static RemoteKeys listen(List<String> adapterNames, Consumer<Event> onEvent) {
        RemoteKeys keys = new RemoteKeys();
        String devices;
        try {
            devices = Files.readString(Path.of("/proc/bus/input/devices"));
        } catch (IOException e) {
            LOG.info("TV remote: cannot list input devices: " + e.getMessage());
            return keys;
        }
        for (String name : adapterNames) {
            inputDevice(devices, name).ifPresentOrElse(device -> {
                int fd = Libc.open(device.toString(), Libc.O_RDONLY);
                if (fd < 0) {
                    LOG.warning("TV remote: cannot read " + device
                            + (Files.exists(device) && !Files.isReadable(device)
                            ? " (add this user to the input group: sudo usermod -aG input $USER)" : ""));
                    return;
                }
                keys.fds.add(fd);
                Thread.ofPlatform().daemon().name("orbit-remote-" + name)
                        .start(() -> keys.read(fd, device, name, onEvent));
            }, () -> LOG.info(() -> "TV remote: no input device for " + name
                    + "; the kernel may lack CEC remote support"));
        }
        return keys;
    }

    /** True when at least one remote device is being read. */
    boolean found() {
        return !fds.isEmpty();
    }

    /**
     * Takes the devices for this process alone, or gives them back: while grabbed, nobody else (Moonlight, the console)
     * sees their keys. Devices already as asked are left alone. @return true if every device is now as asked
     */
    synchronized boolean grab(boolean exclusive) {
        boolean all = !fds.isEmpty();
        for (int fd : fds) {
            if (grabbed.contains(fd) == exclusive) {
                continue;
            }
            boolean done = Libc.ioctl(fd, EVIOCGRAB, exclusive ? 1 : 0);
            if (done == exclusive) {
                grabbed.add(fd);
            } else {
                grabbed.remove(fd);
            }
            all &= done;
        }
        return all;
    }

    private void read(int fd, Path device, String name, Consumer<Event> onEvent) {
        int size = eventSize();
        LOG.info(() -> "TV remote: listening for the remote's keys on " + device + " (" + name + ")");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(size);
            while (Libc.read(fd, buffer) == size) {
                byte[] event = buffer.toArray(ValueLayout.JAVA_BYTE);
                keyEvent(event).ifPresent(e -> {
                    LOG.finer(() -> "TV remote: " + e);
                    onEvent.accept(e);
                });
            }
            LOG.info(() -> "TV remote: " + device + " went away");
        } catch (IOException e) {
            LOG.warning("TV remote: cannot read " + device + ": " + e.getMessage());
        } finally {
            fds.remove(Integer.valueOf(fd));
            grabbed.remove(fd);
            Libc.close(fd);
        }
    }

    /** The {@code /dev/input/event*} device named {@code name} in {@code /proc/bus/input/devices}. */
    static Optional<Path> inputDevice(String procDevices, String name) {
        for (String block : procDevices.split("\\n\\s*\\n")) {
            Matcher n = NAME.matcher(block);
            Matcher e = EVENT.matcher(block);
            if (n.find() && n.group(1).equals(name) && e.find()) {
                return Optional.of(Path.of("/dev/input", e.group(1)));
            }
        }
        return Optional.empty();
    }

    /**
     * Size of the kernel's {@code struct input_event}: a timestamp of two longs, then a 16-bit type, a 16-bit code and
     * a 32-bit value.
     */
    static int eventSize() {
        return "32".equals(System.getProperty("sun.arch.data.model")) ? 16 : 24;
    }

    /** The key event in a {@code struct input_event}; empty for other kinds of events. */
    static Optional<Event> keyEvent(byte[] event) {
        ByteBuffer b = ByteBuffer.wrap(event).order(ByteOrder.nativeOrder());
        int at = event.length - 8;
        if (Short.toUnsignedInt(b.getShort(at)) != EV_KEY) {
            return Optional.empty();
        }
        return Optional.of(new Event(Short.toUnsignedInt(b.getShort(at + 2)), b.getInt(at + 4)));
    }
}
