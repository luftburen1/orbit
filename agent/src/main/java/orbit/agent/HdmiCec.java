package orbit.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Turns the TV on and switches it to this client's HDMI input before a stream, over HDMI-CEC through the kernel's
 * {@code /dev/cec*} devices ({@link CecDevice}), e.g. on a Raspberry Pi. Nothing here fails a stream: a client
 * without CEC, or a TV that doesn't answer, streams as before.
 */
class HdmiCec {

    private static final Logger LOG = Logger.getLogger(HdmiCec.class.getName());

    private static final Path DEV = Path.of("/dev");
    static final String OSD_NAME = "Orbit";
    /** The physical address f.f.f.f: nothing is connected, or the display gave no EDID. */
    static final int NO_PHYS_ADDR = 0xffff;
    /** Logical addresses: the TV, and every device. */
    static final int TV = 0;
    static final int BROADCAST = 15;
    // Opcodes (CEC 1.4 and 2.0), from linux/cec.h.
    static final int IMAGE_VIEW_ON = 0x04;
    static final int ACTIVE_SOURCE = 0x82;
    static final int GIVE_DEVICE_POWER_STATUS = 0x8f;
    static final int REPORT_POWER_STATUS = 0x90;
    /**
     * How long a TV may take from standby to on and to give its EDID; projectors warm up for many seconds, and
     * Moonlight started before the EDID finds no usable display mode and hangs. With Moonlight's own timeouts
     * (45 s + 135 s) this stays within the host's 4 min for a start.
     */
    static final Duration WAKE_TIMEOUT = Duration.ofSeconds(45);
    static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    /** The TV's power as it reported it; {@link #NO_ANSWER} when nothing acknowledged, {@link #UNKNOWN} otherwise. */
    enum Power { ON, STANDBY, TO_ON, TO_STANDBY, NO_ANSWER, UNKNOWN }

    /** One open CEC adapter: {@link CecDevice}, or a stand-in in tests. */
    interface Port extends AutoCloseable {
        /** The adapter's name, e.g. {@code vc4-hdmi-0}; its remote-control input device has the same name. */
        String adapterName() throws IOException;

        /** This port's HDMI address, e.g. 0x1000 for 1.0.0.0; {@link #NO_PHYS_ADDR} without a display. */
        int physicalAddress() throws IOException;

        /** The logical address this adapter has claimed, or -1 while it has none. */
        int logicalAddress() throws IOException;

        /** Claims a logical address as a playback device named {@code osdName}; returns once that's done or failed. */
        void claimPlayback(String osdName) throws IOException;

        /**
         * Sends {@code opcode} with {@code operands} to {@code destination} from this adapter's logical address.
         *
         * @param replyOpcode the answer to wait for, or 0 for none
         */
        Reply transmit(int destination, int opcode, byte[] operands, int replyOpcode) throws IOException;

        @Override
        void close();
    }

    /** Opens a CEC device. */
    interface Opener {
        Port open(String device) throws IOException;
    }

    /**
     * @param sent     it went out, and a device it was meant for acknowledged it
     * @param operands the reply's operands, when a reply was asked for and came; else null
     */
    record Reply(boolean sent, byte[] operands) {
    }

    /**
     * An adapter as found.
     *
     * @param name       the adapter's name, e.g. {@code vc4-hdmi-0}
     * @param configured it has a logical address, so it can send
     */
    record Adapter(String device, String name, int physAddr, boolean configured) {
        /** Whether a display is connected to this port. */
        boolean connected() {
            return physAddr != NO_PHYS_ADDR;
        }
    }

    private final Opener opener;
    /** Where the CEC devices are: {@code /dev}, or a directory of stand-ins in tests. */
    private final Path devDir;
    /** A configured device, e.g. {@code /dev/cec1}; null to use the first port with a display. */
    private final String configuredDevice;
    Duration wakeTimeout = WAKE_TIMEOUT;
    Duration pollInterval = POLL_INTERVAL;

    HdmiCec(Opener opener, Path devDir, String configuredDevice) {
        this.opener = opener;
        this.devDir = devDir;
        this.configuredDevice = configuredDevice;
    }

    /**
     * Sets up HDMI-CEC as {@code hdmiCec} in agent.json says: off, auto (the default) or a device path. Empty when
     * it's off or this client can't do it (not Linux, no {@code /dev/cec*}); the reason is logged.
     */
    static Optional<HdmiCec> fromConfig(String setting) {
        if ("off".equalsIgnoreCase(setting)) {
            LOG.info("HDMI-CEC turned off in agent.json");
            return Optional.empty();
        }
        String device = setting == null || "auto".equalsIgnoreCase(setting) ? null : setting;
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("linux")) {
            LOG.fine("HDMI-CEC is only supported on Linux");
            return Optional.empty();
        }
        if (devices(DEV, device).isEmpty()) {
            if (device != null) {
                LOG.warning(() -> "HDMI-CEC: " + device + " from agent.json doesn't exist");
            } else {
                LOG.info("HDMI-CEC: no /dev/cec* device on this client");
            }
            return Optional.empty();
        }
        return Optional.of(new HdmiCec(CecDevice::open, DEV, device));
    }

    /** The configured device if it exists, else every {@code cec*} device in {@code dev}, in name order. */
    static List<String> devices(Path dev, String configured) {
        if (configured != null) {
            return Files.exists(Path.of(configured)) ? List.of(configured) : List.of();
        }
        try (Stream<Path> files = Files.list(dev)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("cec\\d+"))
                    .sorted()
                    .map(n -> dev.resolve(n).toString())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Checks whether a TV answers, and claims a CEC address as "Orbit" if no one has yet, so the TV can list this
     * client. Only logs: the TV may be unplugged now and plugged in by the next stream.
     */
    void checkAtStartup() {
        Optional<Adapter> adapter = connectedAdapter();
        if (adapter.isEmpty()) {
            return;
        }
        String device = adapter.get().device();
        try (Port port = opener.open(device)) {
            Power power = powerStatus(port);
            if (power == Power.NO_ANSWER) {
                LOG.info(() -> "HDMI-CEC: no TV answers on " + device + "; is CEC turned on in the TV's settings"
                        + " (Anynet+, Bravia Sync, SimpLink, …)? Checked again before each stream");
            } else {
                LOG.info(() -> "HDMI-CEC: TV answers on " + device + " (this client is HDMI "
                        + physAddrText(adapter.get().physAddr()) + ", TV " + describe(power)
                        + "); switching it to this input before streams");
            }
        } catch (IOException e) {
            LOG.warning(() -> "HDMI-CEC: " + e.getMessage());
        }
    }

    /**
     * Turns the TV on if needed, waits until it is (so Moonlight finds the display's modes), and makes it show this
     * client's input.
     *
     * @return whether the TV took the switch; false if there is no CEC display or the TV doesn't answer
     */
    boolean switchTvToThisInput() throws InterruptedException {
        Optional<Adapter> found = connectedAdapter();
        if (found.isEmpty()) {
            return false;
        }
        String device = found.get().device();
        try (Port port = opener.open(device)) {
            return switchTvToThisInput(port, device);
        } catch (IOException e) {
            LOG.warning(() -> "HDMI-CEC: couldn't switch the TV to this input: " + e.getMessage());
            return false;
        }
    }

    private boolean switchTvToThisInput(Port port, String device) throws IOException, InterruptedException {
        Power power = powerStatus(port);
        if (power == Power.NO_ANSWER) {
            LOG.info(() -> "HDMI-CEC: no TV answers on " + device + "; not switching its input");
            return false;
        }
        long deadline = System.nanoTime() + wakeTimeout.toNanos();
        // Also when the TV is on: some TVs only switch inputs for Active Source after Image View On. A TV that
        // doesn't report its power (UNKNOWN) isn't waited for.
        port.transmit(TV, IMAGE_VIEW_ON, new byte[0], 0);
        if (power != Power.ON && power != Power.UNKNOWN) {
            LOG.info(() -> "HDMI-CEC: turning the TV on (it was " + describe(power) + ")");
            waitUntilOn(port, deadline);
        }
        // Turning on can briefly unplug HDMI and with it the physical address (the kernel then claims the logical
        // address again by itself), so both are read again.
        while (true) {
            int physAddr = port.physicalAddress();
            if (physAddr != NO_PHYS_ADDR && port.logicalAddress() >= 0) {
                byte[] operands = {(byte) (physAddr >> 8), (byte) physAddr};
                if (port.transmit(BROADCAST, ACTIVE_SOURCE, operands, 0).sent()) {
                    LOG.info(() -> "HDMI-CEC: switched the TV to this input (HDMI " + physAddrText(physAddr) + ")");
                    return true;
                }
            }
            if (System.nanoTime() >= deadline) {
                LOG.warning(() -> "HDMI-CEC: couldn't switch the TV to this input on " + device);
                return false;
            }
            Thread.sleep(pollInterval.toMillis());
        }
    }

    /** Polls until the TV says it's on, or the deadline passes. */
    private void waitUntilOn(Port port, long deadline) throws IOException, InterruptedException {
        long start = System.nanoTime();
        while (System.nanoTime() < deadline) {
            Thread.sleep(pollInterval.toMillis());
            if (powerStatus(port) == Power.ON) {
                long ms = (System.nanoTime() - start) / 1_000_000;
                LOG.info(() -> "HDMI-CEC: the TV is on after " + ms + " ms");
                return;
            }
        }
        LOG.info(() -> "HDMI-CEC: the TV didn't report being on within " + wakeTimeout.toMillis() / 1000.0
                + " s; going ahead");
    }

    private static String describe(Power power) {
        return power.name().toLowerCase().replace('_', ' ');
    }

    /** E.g. 0x1000 as "1.0.0.0". */
    static String physAddrText(int physAddr) {
        return (physAddr >> 12 & 0xf) + "." + (physAddr >> 8 & 0xf) + "." + (physAddr >> 4 & 0xf) + "."
                + (physAddr & 0xf);
    }

    /**
     * The configured port, else the first port with a display connected, configured as a playback device named
     * "Orbit" unless something already claimed an address on it.
     */
    Optional<Adapter> connectedAdapter() {
        List<String> devices = devices(devDir, configuredDevice);
        for (String device : devices) {
            Optional<Adapter> adapter = adapter(device, true);
            if (adapter.isPresent()) {
                return adapter;
            }
        }
        LOG.info(() -> "HDMI-CEC: no display connected to " + String.join(", ", devices));
        return Optional.empty();
    }

    /** The names of all CEC adapters (whether a display is connected or not), for {@link RemoteKeys}. */
    List<String> adapterNames() {
        List<String> names = new ArrayList<>();
        for (String device : devices(devDir, configuredDevice)) {
            adapter(device, false).map(Adapter::name).ifPresent(names::add);
        }
        return names;
    }

    /**
     * What the adapter at {@code device} is; empty if it can't be opened or read.
     *
     * @param connected only one with a display, given an address if it has none; else empty
     */
    private Optional<Adapter> adapter(String device, boolean connected) {
        try (Port port = opener.open(device)) {
            Adapter adapter = new Adapter(device, port.adapterName(), port.physicalAddress(),
                    port.logicalAddress() >= 0);
            if (!connected) {
                return Optional.of(adapter);
            }
            if (!adapter.connected()) {
                return Optional.empty();
            }
            if (!adapter.configured()) {
                LOG.info(() -> "HDMI-CEC: claiming an address on " + device + " as playback device \"" + OSD_NAME
                        + "\"");
                port.claimPlayback(OSD_NAME);
                if (port.logicalAddress() < 0) {
                    LOG.warning(() -> "HDMI-CEC: couldn't claim an address on " + device);
                    return Optional.empty();
                }
                adapter = new Adapter(device, adapter.name(), adapter.physAddr(), true);
            }
            return Optional.of(adapter);
        } catch (IOException e) {
            LOG.warning(() -> "HDMI-CEC: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Asks the TV for its power status. */
    static Power powerStatus(Port port) throws IOException {
        Reply reply = port.transmit(TV, GIVE_DEVICE_POWER_STATUS, new byte[0], REPORT_POWER_STATUS);
        if (!reply.sent()) {
            return Power.NO_ANSWER;
        }
        if (reply.operands() == null || reply.operands().length < 1) {
            // Acknowledged but no report, e.g. a TV that doesn't do power status.
            return Power.UNKNOWN;
        }
        return switch (reply.operands()[0]) {
            case 0 -> Power.ON;
            case 1 -> Power.STANDBY;
            case 2 -> Power.TO_ON;
            case 3 -> Power.TO_STANDBY;
            default -> Power.UNKNOWN;
        };
    }
}
