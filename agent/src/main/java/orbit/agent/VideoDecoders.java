package orbit.agent;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import orbit.protocol.Commands;
import orbit.protocol.StreamSettings;

/**
 * Which video codecs this machine decodes in hardware, for the host's stream settings: the V4L2 decoders of boards
 * like the Raspberry Pi, and VA-API (asked through {@code vainfo}) on desktops. Moonlight decodes the other codecs in
 * software, which costs CPU, and may not keep up at high resolutions.
 */
final class VideoDecoders {

    private static final Logger LOG = Logger.getLogger(VideoDecoders.class.getName());

    // videodev2.h: struct v4l2_fmtdesc is index, type, flags, description[32], pixelformat, mbus_code, reserved[3].
    private static final int FMTDESC_SIZE = 64;
    private static final int FMTDESC_PIXELFORMAT = 44;
    private static final long VIDIOC_ENUM_FMT = Libc.iowr('V', 2, FMTDESC_SIZE);
    /** A decoder takes the compressed stream on its output queue: V4L2_BUF_TYPE_VIDEO_OUTPUT and its MPLANE type. */
    private static final int[] OUTPUT_TYPES = {2, 10};

    /** Compressed pixel formats (stateful and stateless) by codec. */
    private static final Map<String, String> V4L2_FORMATS = Map.of(
            "H264", "H.264", "S264", "H.264",
            "HEVC", "HEVC", "S265", "HEVC",
            "AV1F", "AV1");
    private static final Map<String, String> VAAPI_PROFILES = Map.of(
            "VAProfileH264", "H.264", "VAProfileHEVC", "HEVC", "VAProfileAV1", "AV1");

    private VideoDecoders() {
    }

    /** The codecs, in {@link StreamSettings#VIDEO_CODECS} order; empty if this machine can't tell. */
    static Optional<List<String>> probe() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            return Optional.empty();
        }
        Set<String> found = new LinkedHashSet<>();
        boolean known = false;
        try {
            List<String> v4l2 = v4l2Formats();
            known = !v4l2.isEmpty();
            v4l2.stream().map(V4L2_FORMATS::get).filter(c -> c != null).forEach(found::add);
        } catch (IOException | LinkageError | RuntimeException e) {
            LOG.log(Level.FINE, "cannot ask V4L2 for video decoders", e);
        }
        Optional<String> vainfo = vainfo();
        if (vainfo.isPresent()) {
            known = true;
            found.addAll(fromVainfo(vainfo.get()));
        }
        return known ? Optional.of(StreamSettings.VIDEO_CODECS.stream().filter(found::contains).toList())
                : Optional.empty();
    }

    /** The compressed formats the V4L2 devices take as fourccs; empty when there's no decoder. */
    private static List<String> v4l2Formats() throws IOException {
        List<String> formats = new ArrayList<>();
        try (DirectoryStream<Path> devices = Files.newDirectoryStream(Path.of("/dev"), "video*");
                Arena arena = Arena.ofConfined()) {
            MemorySegment desc = arena.allocate(FMTDESC_SIZE);
            for (Path device : devices) {
                int fd = Libc.open(device.toString(), Libc.O_RDWR | Libc.O_NONBLOCK);
                if (fd < 0) {
                    continue;
                }
                try {
                    for (int type : OUTPUT_TYPES) {
                        for (int index = 0; index < 64; index++) {
                            desc.fill((byte) 0);
                            desc.set(ValueLayout.JAVA_INT, 0, index);
                            desc.set(ValueLayout.JAVA_INT, 4, type);
                            if (!Libc.ioctl(fd, VIDIOC_ENUM_FMT, desc)) {
                                break;
                            }
                            formats.add(fourcc(desc.get(ValueLayout.JAVA_INT, FMTDESC_PIXELFORMAT)));
                        }
                    }
                } finally {
                    Libc.close(fd);
                }
            }
        }
        return formats;
    }

    static String fourcc(int format) {
        byte[] b = {(byte) format, (byte) (format >> 8), (byte) (format >> 16), (byte) (format >> 24)};
        return new String(b, StandardCharsets.US_ASCII);
    }

    /** {@code vainfo}'s output for the render node; empty when it isn't installed or finds no driver. */
    private static Optional<String> vainfo() {
        try {
            Commands.Result result = Commands.run(List.of("vainfo", "--display", "drm"), Duration.ofSeconds(10));
            return result.ok() ? Optional.of(result.output()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** The codecs a {@code vainfo} listing decodes: profiles with the VLD (decoding) entry point. */
    static Set<String> fromVainfo(String output) {
        Set<String> codecs = new LinkedHashSet<>();
        for (String line : output.lines().toList()) {
            String[] parts = line.strip().split("\\s*:\\s*");
            if (parts.length == 2 && parts[1].equals("VAEntrypointVLD")) {
                VAAPI_PROFILES.forEach((prefix, codec) -> {
                    if (parts[0].startsWith(prefix)) {
                        codecs.add(codec);
                    }
                });
            }
        }
        return codecs;
    }
}
