package orbit.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

class VideoDecodersTest {

    @Test
    void readsDecodingProfilesFromVainfo() {
        String output = """
                vainfo: Driver version: Intel iHD driver for Intel(R) Gen Graphics - 26.2.2 ()
                vainfo: Supported profile and entrypoints
                      VAProfileNone                   :\tVAEntrypointVideoProc
                      VAProfileH264Main               :\tVAEntrypointVLD
                      VAProfileH264Main               :\tVAEntrypointEncSlice
                      VAProfileHEVCMain10             :\tVAEntrypointEncSlice
                      VAProfileAV1Profile0            :\tVAEntrypointVLD
                      VAProfileH264ConstrainedBaseline:\tVAEntrypointVLD
                """;
        assertEquals(Set.of("H.264", "AV1"), VideoDecoders.fromVainfo(output), "HEVC here only encodes");
    }

    @Test
    void readsFourccs() {
        assertEquals("S265", VideoDecoders.fourcc('S' | '2' << 8 | '6' << 16 | '5' << 24));
    }
}
