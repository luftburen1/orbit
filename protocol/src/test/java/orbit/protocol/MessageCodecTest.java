package orbit.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class MessageCodecTest {

    private static final StreamSettings SETTINGS = new StreamSettings(2560, 1440, 120, 50_000, List.of("--hdr"));

    static Stream<Message> everyMessage() {
        return Stream.of(
                new Message.Hello(Protocol.VERSION, "a1", "livingroom", "tok", "6.1.0"),
                new Message.Hello(Protocol.VERSION, "a1", "livingroom", "tok", "6.1.0", "0.1.0"),
                new Message.Hello(Protocol.VERSION, "a1", "livingroom", "tok", "6.1.0", "0.1.0", List.of("HEVC")),
                new Message.Welcome("tok", new StreamSettings(1920, 1080, 60, 20_000, List.of(), "AV1")),
                new Message.Welcome("tok", new StreamSettings(1920, 1080, 60, 20_000, List.of(), null, true, false,
                        "7.1-surround", true)),
                new Message.SunshineState(false),
                new Message.PairingDone("c1", true),
                new Message.CheckPairing(),
                new Message.Status(ClientState.ERROR, null, false, "Moonlight 6.1.0", "Failed to connect to gamingpc"),
                new Message.Hello(Protocol.VERSION, "a1", "livingroom", null, null),
                new Message.Status(ClientState.STREAMING, "Desktop"),
                new Message.Status(ClientState.AVAILABLE, null),
                new Message.PairPin("c1", "1234"),
                Message.CommandResult.success("c1"),
                Message.CommandResult.failure("c2", "moonlight exited with 1"),
                new Message.Welcome("tok", SETTINGS),
                new Message.Welcome(null, StreamSettings.DEFAULT),
                new Message.Welcome(null, StreamSettings.DEFAULT, true, false, false, false),
                new Message.StreamRequest(),
                new Message.StreamRequest("Steam Big Picture"),
                new Message.AppListRequest(),
                new Message.StopRequest(),
                new Message.AppList(java.util.List.of("Desktop", "Steam Big Picture"), "Desktop", null),
                new Message.AppList(java.util.List.of(), null, "can't read Sunshine's apps"),
                new Message.AppList(java.util.List.of("Desktop", "Zwift"), null, null,
                        java.util.Arrays.asList(null, "iVBORw0KGgo=")),
                new Message.Welcome("tok", SETTINGS, true, true, false, false),
                new Message.Welcome(null, SETTINGS, true, true, true, false),
                new Message.Welcome(null, SETTINGS, true, true, true, true),
                new Message.Welcome(null, SETTINGS, true, true, true, true, true),
                new Message.RemoteAction(Message.RemoteAction.MOUSE_MODE),
                new Message.RemoteAction(Message.RemoteAction.STOP_QUESTION_OK),
                new Message.StopCheckRequest(true),
                new Message.StopCheck("Steam Big Picture", true),
                new Message.StopCheck("", false),
                new Message.StopCheck(null, false),
                new Message.FocusRequest(true),
                new Message.FocusRequest(false),
                new Message.StreamRequestRefused("Sunshine isn't running on the host"),
                new Message.Pending(),
                new Message.Rejected("bad token"),
                new Message.StartStream("c3", "Steam Big Picture", SETTINGS),
                new Message.StartStream("c3", "Steam Big Picture", SETTINGS, true),
                new Message.StopStream("c4"),
                new Message.StopStream("c4", true),
                new Message.BeginPairing("c5"),
                new Message.ApplySettings(SETTINGS));
    }

    @ParameterizedTest
    @MethodSource("everyMessage")
    void roundTrips(Message message) throws ProtocolException {
        assertEquals(message, MessageCodec.decode(MessageCodec.encode(message)));
    }

    @Test
    void usesTypeDiscriminatorAndOmitsNulls() {
        String json = MessageCodec.encode(new Message.Status(ClientState.AVAILABLE, null));
        assertEquals("{\"type\":\"status\",\"state\":\"AVAILABLE\"}", json);
    }

    @Test
    void ignoresUnknownFields() throws ProtocolException {
        Message decoded = MessageCodec.decode("{\"type\":\"stopStream\",\"cmdId\":\"c\",\"future\":1}");
        assertEquals(new Message.StopStream("c"), decoded);
    }

    @Test
    void welcomeFromAnOlderHostTakesNoStreamRequests() throws ProtocolException {
        Message.Welcome decoded = (Message.Welcome) MessageCodec.decode("{\"type\":\"welcome\",\"token\":\"tok\"}");
        assertEquals(false, decoded.streamRequests());
        assertEquals(false, decoded.streamControl());
        assertEquals(false, decoded.focusRequests());
        assertEquals(false, decoded.remoteActions());
    }

    @Test
    void settingsWithoutACodecOrWithAnUnknownOneLeaveItToMoonlight() throws ProtocolException {
        Message.Welcome old = (Message.Welcome) MessageCodec.decode("{\"type\":\"welcome\",\"settings\":{"
                + "\"width\":1920,\"height\":1080,\"fps\":60,\"bitrateKbps\":20000}}");
        assertEquals(null, old.settings().videoCodec());
        assertEquals(null, old.settings().vsync(), "settings from older hosts leave V-Sync to Moonlight");
        assertEquals(null, old.settings().audioConfig());
        assertEquals(null, new StreamSettings(1920, 1080, 60, 20_000, List.of(), "VP9").videoCodec());
        assertEquals("HEVC", new StreamSettings(1920, 1080, 60, 20_000, List.of(), "HEVC").videoCodec());
    }

    @Test
    void streamRequestWithoutAnAppLooksAsBefore() {
        assertEquals("{\"type\":\"streamRequest\"}", MessageCodec.encode(new Message.StreamRequest()));
    }

    @Test
    void helloFromAnOlderAgentHasNoAgentVersion() throws ProtocolException {
        Message decoded = MessageCodec.decode("{\"type\":\"hello\",\"protocolVersion\":" + Protocol.VERSION
                + ",\"agentId\":\"a1\",\"hostname\":\"livingroom\",\"moonlightVersion\":\"6.1.0\"}");
        assertEquals(new Message.Hello(Protocol.VERSION, "a1", "livingroom", null, "6.1.0", null), decoded);
    }

    @Test
    void statusWithMoonlightMissingRoundTripsAndOldStatusDefaultsToPresent() throws ProtocolException {
        Message.Status missing = new Message.Status(ClientState.ERROR, null, true, null);
        assertEquals("{\"type\":\"status\",\"state\":\"ERROR\",\"moonlightMissing\":true}",
                MessageCodec.encode(missing));
        assertEquals(missing, MessageCodec.decode(MessageCodec.encode(missing)));
        assertEquals(new Message.Status(ClientState.ERROR, null),
                MessageCodec.decode("{\"type\":\"status\",\"state\":\"ERROR\"}"), "older agents");
    }

    @Test
    void streamsBlockedIsSentOnlyWhileTrue() throws ProtocolException {
        Message.Status blocked = new Message.Status(ClientState.AVAILABLE, null, false, null, null, true);
        assertEquals("{\"type\":\"status\",\"state\":\"AVAILABLE\",\"streamsBlocked\":true}",
                MessageCodec.encode(blocked));
        assertEquals(blocked, MessageCodec.decode(MessageCodec.encode(blocked)));
        assertEquals("{\"type\":\"status\",\"state\":\"AVAILABLE\"}",
                MessageCodec.encode(new Message.Status(ClientState.AVAILABLE, null)), "older hosts never see it");
    }

    @Test
    void remoteIsSentOnlyWhileTrue() throws ProtocolException {
        Message.Status remote = new Message.Status(ClientState.AVAILABLE, null, false, null, null, false, true);
        assertEquals("{\"type\":\"status\",\"state\":\"AVAILABLE\",\"remote\":true}", MessageCodec.encode(remote));
        assertEquals(remote, MessageCodec.decode(MessageCodec.encode(remote)));
        assertEquals(new Message.Status(ClientState.AVAILABLE, null),
                MessageCodec.decode("{\"type\":\"status\",\"state\":\"AVAILABLE\"}"), "older agents");
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(ProtocolException.class, () -> MessageCodec.decode("not json"));
        assertThrows(ProtocolException.class, () -> MessageCodec.decode("{\"type\":\"nope\"}"));
    }

    @Test
    void rejectsInvalidSettings() {
        var e = assertThrows(ProtocolException.class, () -> MessageCodec.decode(
                "{\"type\":\"applySettings\",\"settings\":{\"width\":0,\"height\":1080,\"fps\":60,\"bitrateKbps\":1}}"));
        assertTrue(e.getMessage().contains("resolution"), e.getMessage());
    }
}
