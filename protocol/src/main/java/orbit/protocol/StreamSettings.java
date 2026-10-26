package orbit.protocol;

import java.util.List;

/**
 * Moonlight stream settings pushed from the host, applied as CLI flags on each stream start. Every setting after
 * {@code extraFlags} is null for Moonlight's own default (no flag), and absent in older settings.
 *
 * @param videoCodec one of {@link #VIDEO_CODECS}
 * @param vsync V-Sync on or off
 * @param framePacing frame pacing on or off
 * @param audioConfig one of {@link #AUDIO_CONFIGS}
 * @param performanceOverlay Moonlight's statistics overlay on or off
 */
public record StreamSettings(int width, int height, int fps, int bitrateKbps, List<String> extraFlags,
        String videoCodec, Boolean vsync, Boolean framePacing, String audioConfig, Boolean performanceOverlay) {

    /** The codecs Moonlight's {@code --video-codec} takes, spelled as it takes them. */
    public static final List<String> VIDEO_CODECS = List.of("H.264", "HEVC", "AV1");
    /** What Moonlight's {@code --audio-config} takes. */
    public static final List<String> AUDIO_CONFIGS = List.of("stereo", "5.1-surround", "7.1-surround");

    public static final StreamSettings DEFAULT = new StreamSettings(1920, 1080, 60, 20_000, List.of());

    public StreamSettings {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("resolution must be positive: " + width + "x" + height);
        }
        if (fps <= 0) {
            throw new IllegalArgumentException("fps must be positive: " + fps);
        }
        if (bitrateKbps <= 0) {
            throw new IllegalArgumentException("bitrate must be positive: " + bitrateKbps);
        }
        extraFlags = extraFlags == null ? List.of() : List.copyOf(extraFlags);
        // A value this version doesn't know (e.g. from a newer host) leaves the choice to Moonlight.
        videoCodec = videoCodec != null && VIDEO_CODECS.contains(videoCodec) ? videoCodec : null;
        audioConfig = audioConfig != null && AUDIO_CONFIGS.contains(audioConfig) ? audioConfig : null;
    }

    public StreamSettings(int width, int height, int fps, int bitrateKbps, List<String> extraFlags) {
        this(width, height, fps, bitrateKbps, extraFlags, null);
    }

    public StreamSettings(int width, int height, int fps, int bitrateKbps, List<String> extraFlags,
            String videoCodec) {
        this(width, height, fps, bitrateKbps, extraFlags, videoCodec, null, null, null, null);
    }

    public String resolution() {
        return width + "x" + height;
    }
}
