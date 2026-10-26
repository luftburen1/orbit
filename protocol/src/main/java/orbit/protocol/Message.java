package orbit.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * Every message exchanged over the agent WebSocket. Serialized as JSON with a {@code "type"} discriminator.
 *
 * <p>Handshake: the agent sends {@link Hello}; the host answers {@link Welcome} (approved, possibly carrying a newly
 * issued token), {@link Pending} (waiting for approval in the host UI) or {@link Rejected}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Message.Hello.class, name = "hello"),
        @JsonSubTypes.Type(value = Message.Status.class, name = "status"),
        @JsonSubTypes.Type(value = Message.PairPin.class, name = "pairPin"),
        @JsonSubTypes.Type(value = Message.CommandResult.class, name = "commandResult"),
        @JsonSubTypes.Type(value = Message.Welcome.class, name = "welcome"),
        @JsonSubTypes.Type(value = Message.Pending.class, name = "pending"),
        @JsonSubTypes.Type(value = Message.Rejected.class, name = "rejected"),
        @JsonSubTypes.Type(value = Message.StartStream.class, name = "startStream"),
        @JsonSubTypes.Type(value = Message.StopStream.class, name = "stopStream"),
        @JsonSubTypes.Type(value = Message.BeginPairing.class, name = "beginPairing"),
        @JsonSubTypes.Type(value = Message.ApplySettings.class, name = "applySettings"),
        @JsonSubTypes.Type(value = Message.SunshineState.class, name = "sunshineState"),
        @JsonSubTypes.Type(value = Message.PairingDone.class, name = "pairingDone"),
        @JsonSubTypes.Type(value = Message.CheckPairing.class, name = "checkPairing"),
        @JsonSubTypes.Type(value = Message.StreamRequest.class, name = "streamRequest"),
        @JsonSubTypes.Type(value = Message.StreamRequestRefused.class, name = "streamRequestRefused"),
        @JsonSubTypes.Type(value = Message.AppListRequest.class, name = "appListRequest"),
        @JsonSubTypes.Type(value = Message.AppList.class, name = "appList"),
        @JsonSubTypes.Type(value = Message.StopRequest.class, name = "stopRequest"),
        @JsonSubTypes.Type(value = Message.FocusRequest.class, name = "focusRequest"),
        @JsonSubTypes.Type(value = Message.RemoteAction.class, name = "remoteAction"),
        @JsonSubTypes.Type(value = Message.StopCheckRequest.class, name = "stopCheckRequest"),
        @JsonSubTypes.Type(value = Message.StopCheck.class, name = "stopCheck"),
})
public sealed interface Message {

    /** Messages sent by an agent. */
    sealed interface FromAgent extends Message {
    }

    /** Messages sent by the host. */
    sealed interface FromHost extends Message {
    }

    /** Host messages that must be answered with a {@link CommandResult} carrying the same {@code cmdId}. */
    sealed interface Command extends FromHost {
        String cmdId();
    }

    // ---- agent -> host ----

    /**
     * First message on every connection. {@code token} is null until the host has approved this agent. The versions
     * are null when unknown; agents before 0.1.0 don't send {@code agentVersion}.
     *
     * @param hardwareDecoders the {@link StreamSettings#VIDEO_CODECS} this client decodes in hardware (others it
     *                         decodes in software); null when it can't tell, or the agent is older
     */
    record Hello(int protocolVersion, String agentId, String hostname, String token, String moonlightVersion,
            String agentVersion, List<String> hardwareDecoders) implements FromAgent {

        public Hello(int protocolVersion, String agentId, String hostname, String token, String moonlightVersion) {
            this(protocolVersion, agentId, hostname, token, moonlightVersion, null);
        }

        public Hello(int protocolVersion, String agentId, String hostname, String token, String moonlightVersion,
                String agentVersion) {
            this(protocolVersion, agentId, hostname, token, moonlightVersion, agentVersion, null);
        }
    }

    /**
     * The agent's state, sent on change and every 30 s.
     *
     * @param moonlightMissing Moonlight isn't installed (or the configured command can't run); the state is then
     *                         {@link ClientState#ERROR}. Agents before 0.1.0 don't send it.
     * @param moonlightVersion as {@code moonlight --version} prints it, or null; lets the host learn the version when
     *                         Moonlight is installed while the agent runs
     * @param problem          why the state is {@link ClientState#ERROR}, in Moonlight's words where it gave some, or
     *                         null
     * @param streamsBlocked   someone at the client turned off streams from the host (its tray's "Don't allow
     *                         streams"); start commands are refused. Sent only while true, so older hosts never see it
     * @param remote           the agent reads the TV's remote over HDMI-CEC, so the client can be used with it. Sent
     *                         only while true; older hosts ignore it
     */
    record Status(ClientState state, String currentApp,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean moonlightMissing, String moonlightVersion,
            String problem, @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean streamsBlocked,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean remote)
            implements FromAgent {

        public Status(ClientState state, String currentApp) {
            this(state, currentApp, false, null, null);
        }

        public Status(ClientState state, String currentApp, boolean moonlightMissing, String moonlightVersion) {
            this(state, currentApp, moonlightMissing, moonlightVersion, null);
        }

        public Status(ClientState state, String currentApp, boolean moonlightMissing, String moonlightVersion,
                String problem) {
            this(state, currentApp, moonlightMissing, moonlightVersion, problem, false);
        }

        public Status(ClientState state, String currentApp, boolean moonlightMissing, String moonlightVersion,
                String problem, boolean streamsBlocked) {
            this(state, currentApp, moonlightMissing, moonlightVersion, problem, streamsBlocked, false);
        }
    }

    /** PIN that the agent's {@code moonlight pair} is waiting on; the host submits it to Sunshine. */
    record PairPin(String cmdId, String pin) implements FromAgent {
    }

    /**
     * Someone at the client asked for a stream, e.g. with the TV remote's Play button; the host starts it as if Start
     * were clicked. Hosts before 0.1.0 close the connection on a message type they don't know, so the agent sends it
     * only to hosts whose {@link Welcome} says {@code streamRequests}.
     *
     * @param app the Sunshine app to start, picked on the client; null for the client's last app. Hosts without
     *            {@code streamControl} ignore it and start the last app
     */
    record StreamRequest(String app) implements FromAgent {

        public StreamRequest() {
            this(null);
        }
    }

    /**
     * Asks for Sunshine's apps, to pick one on the client; the host answers with an {@link AppList}. Sent only to
     * hosts whose {@link Welcome} says {@code streamControl}.
     */
    record AppListRequest() implements FromAgent {
    }

    /**
     * Someone at the client stopped the stream, e.g. with the TV remote's Stop button; the host stops it as if Stop
     * were clicked, so the app quits unless other clients still stream it. Sent only to hosts whose {@link Welcome}
     * says {@code streamControl}.
     */
    record StopRequest() implements FromAgent {
    }

    /**
     * Someone at the client wants to stop the stream: asks the host whether that would lose anything, so they can be
     * asked first. The host answers with a {@link StopCheck}. Sent only to hosts whose {@link Welcome} says
     * {@code stopChecks}.
     *
     * @param remote with the TV remote: the host shows the question on the streamed monitor if it can, and says so
     */
    record StopCheckRequest(boolean remote) implements FromAgent {
    }

    /**
     * The client's TV remote is about to send keys or move the mouse after a pause: if the host's focus is off the
     * monitor Sunshine streams, e.g. because someone at the host clicked elsewhere, the host gives it back to the
     * stream's window there. Sent only to hosts whose {@link Welcome} says {@code focusRequests}.
     *
     * @param pointer the remote moves the mouse: the cursor should be on the stream too
     */
    record FocusRequest(boolean pointer) implements FromAgent {
    }

    /**
     * The client's TV remote did something worth showing during a stream, which the host shows on the streamed
     * monitor, so it appears on the client's screen. Sent only to hosts whose {@link Welcome} says
     * {@code remoteActions}; hosts ignore actions they don't know.
     *
     * @param action {@link #MOUSE_MODE}, {@link #KEY_MODE}, {@link #STOP}, or one of the {@code STOP_QUESTION}s
     */
    record RemoteAction(String action) implements FromAgent {
        public static final String MOUSE_MODE = "mouseMode";
        public static final String KEY_MODE = "keyMode";
        /** Sent a moment before the stop itself, so the notice is seen before the stream closes. */
        public static final String STOP = "stop";
        /** In the stop question a {@link StopCheck} said is shown: OK, which stops, is now selected. */
        public static final String STOP_QUESTION_OK = "stopQuestionOk";
        /** In the stop question: Cancel is now selected, as it is when the question opens. */
        public static final String STOP_QUESTION_CANCEL = "stopQuestionCancel";
        /** The stop question was answered, or timed out: the host closes it. */
        public static final String STOP_QUESTION_CLOSED = "stopQuestionClosed";
    }

    record CommandResult(String cmdId, boolean ok, String error) implements FromAgent {
        public static CommandResult success(String cmdId) {
            return new CommandResult(cmdId, true, null);
        }

        public static CommandResult failure(String cmdId, String error) {
            return new CommandResult(cmdId, false, error);
        }
    }

    // ---- host -> agent ----

    /**
     * Agent is approved. {@code token} is set when a token was just issued and must be stored by the agent.
     *
     * @param streamRequests this host takes {@link StreamRequest}s; older hosts don't send it
     * @param streamControl  this host also takes {@link AppListRequest}s, {@link StopRequest}s, and a
     *                       {@link StreamRequest}'s app; older hosts don't send it
     * @param focusRequests  this host takes {@link FocusRequest}s; older hosts, and hosts that can't move focus,
     *                       don't send it
     * @param remoteActions  this host shows {@link RemoteAction}s; older hosts, and hosts that can't, don't send it
     * @param stopChecks     this host answers {@link StopCheckRequest}s; older hosts don't send it
     */
    record Welcome(String token, StreamSettings settings, boolean streamRequests, boolean streamControl,
            boolean focusRequests, boolean remoteActions, boolean stopChecks) implements FromHost {

        public Welcome(String token, StreamSettings settings) {
            this(token, settings, false, false, false, false);
        }

        public Welcome(String token, StreamSettings settings, boolean streamRequests, boolean streamControl,
                boolean focusRequests, boolean remoteActions) {
            this(token, settings, streamRequests, streamControl, focusRequests, remoteActions, false);
        }
    }

    /** Agent is known but waits for approval in the host UI. The host sends {@link Welcome} once approved. */
    record Pending() implements FromHost {
    }

    /** Connection refused; the host closes the socket after sending this. */
    record Rejected(String reason) implements FromHost {
    }

    /**
     * @param quitAppOnFailure if the stream doesn't start after Moonlight may have launched the app, quit it on the
     *                         host: set when nothing else streams it and it wasn't running for this client before
     */
    record StartStream(String cmdId, String app, StreamSettings settings, boolean quitAppOnFailure)
            implements Command {
        public StartStream(String cmdId, String app, StreamSettings settings) {
            this(cmdId, app, settings, false);
        }
    }

    /**
     * Ends the stream. Normally Moonlight also quits the app on the host; with {@code disconnectOnly} the agent only
     * closes its Moonlight stream and leaves the app running, e.g. because Sunshine is about to restart and the
     * client will reconnect.
     */
    record StopStream(String cmdId, boolean disconnectOnly) implements Command {
        public StopStream(String cmdId) {
            this(cmdId, false);
        }
    }

    record BeginPairing(String cmdId) implements Command {
    }

    /** New default settings for this client; used on the next stream start. */
    record ApplySettings(StreamSettings settings) implements FromHost {
    }

    /**
     * Whether Sunshine runs on the host. Sent before {@link Welcome} and whenever it changes, so the agent doesn't
     * take a failing {@code moonlight list} for "not paired" while Sunshine is down. Agents before 0.1.0 log it as
     * unknown and ignore it.
     */
    record SunshineState(boolean running) implements FromHost {
    }

    /**
     * Check pairing now and report the status: the host just unpaired this client in Sunshine, and the next periodic
     * check could be 30 s away. Sent only then; older agents log it as unknown and notice at their next check.
     */
    record CheckPairing() implements FromHost {
    }

    /**
     * Sunshine's verdict on a pairing's PIN: it only accepts it once pairing has completed. moonlight-qt's
     * {@code pair} shows its result on screen and waits for someone to close it, printing nothing, so this is how
     * the agent knows to close it. Agents before 0.1.0 log it as unknown and ignore it.
     *
     * @param cmdId the {@link BeginPairing} command
     */
    record PairingDone(String cmdId, boolean accepted) implements FromHost {
    }

    /**
     * The host couldn't start the stream a {@link StreamRequest} asked for, e.g. because Sunshine isn't running. Sent
     * only in reply to one, so only to agents that know it.
     */
    record StreamRequestRefused(String reason) implements FromHost {
    }

    /**
     * The answer to a {@link StopCheckRequest}. Sent only in reply to one, so only to agents that know it.
     *
     * @param quitsApp the app a stop would quit on the host, so anything unsaved in it would be lost: empty when it
     *                 isn't known which app runs, null when nothing would be lost (other clients still stream it, or it
     *                 doesn't start anything, like Desktop) and the client can stop without asking
     * @param shown    the host now shows the question on the streamed monitor, with Cancel selected: the client's
     *                 remote answers it with {@link RemoteAction}s. Only for a remote's request
     */
    record StopCheck(String quitsApp, boolean shown) implements FromHost {

        /** What the question says, for the {@code quitsApp} of a check. */
        public static String warning(String quitsApp) {
            String app = quitsApp == null || quitsApp.isEmpty() ? "the app" : quitsApp;
            return "Stopping the stream exits " + app + " on the host. Anything unsaved will be lost.";
        }
    }

    /**
     * Sunshine's apps, in answer to an {@link AppListRequest}.
     *
     * @param apps    their names, in Sunshine's order; empty when they couldn't be read
     * @param lastApp the app this client streamed last, or null
     * @param error   why the apps couldn't be read, or null
     * @param icons   each app's cover from Sunshine as a small PNG in Base64, in the order of {@code apps}, an entry
     *                null where the app has none; null from older hosts. Older agents ignore it
     */
    record AppList(List<String> apps, String lastApp, String error, List<String> icons) implements FromHost {

        public AppList(List<String> apps, String lastApp, String error) {
            this(apps, lastApp, error, null);
        }
    }
}
