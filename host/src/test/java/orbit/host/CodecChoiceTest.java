package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import orbit.host.SettingsDialog.CodecChoice;
import org.junit.jupiter.api.Test;

class CodecChoiceTest {

    private static final List<String> ALL = List.of("H.264", "HEVC", "AV1");

    private static List<String> labels(List<String> encoders, List<String> hardwareDecoders) {
        return CodecChoice.all(encoders, hardwareDecoders).stream().map(CodecChoice::label).toList();
    }

    @Test
    void listsWhatSunshineEncodesAndTheClientDecodesInHardware() {
        assertEquals(List.of("Automatic (Moonlight chooses)", "HEVC (H.265)"),
                labels(List.of("H.264", "HEVC"), List.of("HEVC", "AV1")));
        assertEquals("Codecs Sunshine can encode and pi decodes in hardware.",
                CodecChoice.note("pi", List.of("H.264", "HEVC"), List.of("HEVC", "AV1")));
        assertEquals(null, CodecChoice.all(null, null).getFirst().codec());
    }

    @Test
    void withNothingInHardwareListsWhatSunshineEncodesAsSoftware() {
        assertEquals(List.of("Automatic (Moonlight chooses)", "H.264 · software decoding",
                "HEVC (H.265) · software decoding"), labels(List.of("H.264", "HEVC"), List.of("AV1")));
        assertEquals("Codecs Sunshine can encode; pi decodes them in software, on its CPU.",
                CodecChoice.note("pi", List.of("H.264", "HEVC"), List.of("AV1")));
    }

    @Test
    void anUnknownSideNarrowsNothing() {
        assertEquals(ALL, CodecChoice.listed(null, null));
        assertEquals(List.of("H.264", "HEVC"), CodecChoice.listed(List.of("H.264", "HEVC"), null));
        assertEquals(List.of("HEVC"), CodecChoice.listed(null, List.of("HEVC")));
        assertEquals("Neither Sunshine's encoders nor pi's hardware decoders are known.",
                CodecChoice.note("pi", null, null));
        assertEquals("Codecs Sunshine can encode; pi hasn't said which it decodes in hardware.",
                CodecChoice.note("pi", ALL, null));
    }
}
