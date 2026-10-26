package orbit.host;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import orbit.protocol.ClientState;
import orbit.protocol.StreamSettings;

/**
 * Immutable snapshot of one client, as shown in the UI.
 *
 * @param remote the client is online and reads its TV's remote over HDMI-CEC
 * @param disabledInSunshine every Sunshine pairing under its name is disabled in Sunshine, which refuses its streams
 * @param pairingUuid set only for a Sunshine pairing that no approved client's hostname matches: a Moonlight client
 *                    without an Orbit agent, listed so it can be unpaired
 * @param pairingRequest set only for a Moonlight client without an Orbit agent waiting in Sunshine to be paired,
 *                       listed so it can be paired with the PIN it shows
 */
public record ClientView(
        String agentId,
        String hostname,
        Approval approval,
        boolean online,
        ClientState state,
        String currentApp,
        StreamSettings settings,
        Instant lastSeen,
        String moonlightVersion,
        String agentVersion,
        boolean moonlightMissing,
        boolean sunshineDown,
        String problem,
        boolean streamsBlocked,
        List<String> sunshinePaired,
        List<String> hardwareDecoders,
        boolean remote,
        boolean disabledInSunshine,
        String pairingUuid,
        SunshineClient.PendingPairing pairingRequest) {

    public enum Approval { PENDING, APPROVED }

    /** Their ids, kept apart from agent ids, which are UUIDs too. */
    static final String AGENTLESS_PREFIX = "sunshine-pairing:";
    private static final String REQUEST_PREFIX = "sunshine-request:";

    /**
     * A row for a Sunshine pairing without an Orbit agent, e.g. a phone's Moonlight, or a client paired before Orbit
     * under another name. Orbit can't control it, only unpair it.
     */
    static ClientView agentless(SunshineClient.PairedClient pairing, boolean sunshineDown) {
        return new ClientView(AGENTLESS_PREFIX + pairing.uuid(), pairing.name(), Approval.APPROVED, false, null, null,
                StreamSettings.DEFAULT, null, null, null, false, sunshineDown, null, false, null, null, false,
                !pairing.enabled(), pairing.uuid(), null);
    }

    /** A row for a Moonlight client without an Orbit agent that waits in Sunshine for its PIN. */
    static ClientView pairingRequest(SunshineClient.PendingPairing request, boolean sunshineDown) {
        String name = request.name().isBlank() ? request.address() : request.name();
        return new ClientView(REQUEST_PREFIX + request.id(), name, Approval.APPROVED, false, null, null,
                StreamSettings.DEFAULT, null, null, null, false, sunshineDown, null, false, null, null, false, false,
                null, request);
    }

    /**
     * A Moonlight client without an Orbit agent: paired in Sunshine ({@link #agentless(SunshineClient.PairedClient,
     * boolean)}) or waiting to be ({@link #pairingRequest(SunshineClient.PendingPairing, boolean)}).
     */
    public boolean agentless() {
        return pairingUuid != null || pairingRequest != null;
    }

    public boolean approved() {
        return approval == Approval.APPROVED;
    }

    /**
     * Whether Sunshine has a pairing under this client's name, which is how Orbit pairs clients. False if Sunshine's
     * list is unknown, or the client was paired some other way (e.g. as "moonlight").
     */
    public boolean pairedInSunshine() {
        return sunshinePaired != null && sunshinePaired.stream().anyMatch(n -> n.equalsIgnoreCase(hostname));
    }

    /** Connected and running a stream; an offline client's last reported state doesn't count. */
    public boolean streaming() {
        return online && state == ClientState.STREAMING;
    }

    /** Lines describing the client's software, e.g. for a tooltip: its agent and Moonlight versions. */
    public List<String> softwareLines() {
        if (agentless()) {
            return List.of();
        }
        return List.of(
                "Orbit agent: " + (agentVersion != null ? agentVersion : "unknown (older than 0.1.0)"),
                "Moonlight: " + (online && moonlightMissing ? "not installed"
                        : moonlightVersion != null ? withoutName(moonlightVersion) : "unknown"));
    }

    /**
     * Lines about the client's state for a tooltip: why it reports an error, and Sunshine's paired clients. Orbit
     * pairs clients under their hostname, but ones paired otherwise (e.g. before Orbit) have other names, such as
     * Moonlight's default "moonlight", so a missing name isn't shown as a fault.
     *
     * @see #sunshinePaired the names Sunshine has paired clients under, or null if unknown
     */
    public List<String> stateLines() {
        if (pairingRequest != null) {
            return List.of("Moonlight on " + SunshineClient.plainAddress(pairingRequest.address())
                    + " wants to pair with Sunshine", "Pair asks for the PIN it shows");
        }
        if (agentless()) {
            String paired = disabledInSunshine
                    ? "Paired in Sunshine but disabled there, and" : "Paired in Sunshine, but";
            return List.of(paired + " no Orbit agent has this name", "Orbit can only unpair it");
        }
        List<String> lines = new ArrayList<>();
        if (online && problem != null) {
            lines.add("Problem: " + problem);
        }
        if (online && streamsBlocked) {
            lines.add("Streams are turned off on the client, in its tray icon");
        }
        if (remote) {
            lines.add("TV remote: works with this client (HDMI-CEC)");
        }
        if (approved() && sunshinePaired != null) {
            lines.add(pairedInSunshine() ? "Sunshine: paired as " + hostname
                            + (disabledInSunshine ? ", but disabled there, so it can't stream" : "")
                    : sunshinePaired.isEmpty() ? "Sunshine has no paired clients"
                    : "Sunshine's paired clients: " + String.join(", ", sunshinePaired));
        }
        return lines;
    }

    /** {@code moonlight --version} prints e.g. "Moonlight 6.1.0"; the name is already in the label. */
    private static String withoutName(String version) {
        return version.regionMatches(true, 0, "Moonlight ", 0, 10) ? version.substring(10).strip() : version;
    }

    /** Whether {@link #statusLabel} is a fault to fix ("Sunshine not running", "Moonlight missing", "Error"). */
    public boolean statusIsFault() {
        if (agentless() || !online || !approved()) {
            return false;
        }
        return sunshineDown || moonlightMissing || state == ClientState.ERROR && !disabledInSunshine;
    }

    /** One-word status for display: offline/pending, else the reported state. */
    public String statusLabel() {
        if (pairingRequest != null) {
            return "Wants to pair";
        }
        if (agentless()) {
            return disabledInSunshine ? "Disabled, no agent" : "Paired, no agent";
        }
        if (!online) {
            return "Offline";
        }
        if (!approved()) {
            return "Awaiting approval";
        }
        if (sunshineDown) {
            return "Sunshine not running";
        }
        if (moonlightMissing) {
            return "Moonlight missing";
        }
        if (state == null) {
            return "Connecting";
        }
        // Moonlight takes a client disabled in Sunshine for unpaired.
        if (disabledInSunshine && state != ClientState.STREAMING) {
            return "Disabled in Sunshine";
        }
        return switch (state) {
            case AVAILABLE -> streamsBlocked ? "Not allowing streams" : "Available";
            case STREAMING -> "Streaming";
            case UNPAIRED -> "Not paired";
            case ERROR -> "Error";
        };
    }
}
