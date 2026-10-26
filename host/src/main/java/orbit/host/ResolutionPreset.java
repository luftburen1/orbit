package orbit.host;

import java.util.List;
import java.util.Optional;

/** Common stream resolutions offered in the stream settings. */
record ResolutionPreset(int width, int height, String name) {

    static final List<ResolutionPreset> ALL = List.of(
            new ResolutionPreset(1280, 720, "720p"),
            new ResolutionPreset(1280, 800, "Steam Deck"),
            new ResolutionPreset(1366, 768, "laptop"),
            new ResolutionPreset(1600, 900, "900p"),
            new ResolutionPreset(1920, 1080, "1080p"),
            new ResolutionPreset(1920, 1200, "16:10"),
            new ResolutionPreset(2560, 1080, "21:9 ultrawide"),
            new ResolutionPreset(2560, 1440, "1440p"),
            new ResolutionPreset(2560, 1600, "16:10"),
            new ResolutionPreset(3440, 1440, "21:9 ultrawide"),
            new ResolutionPreset(3840, 2160, "4K"),
            new ResolutionPreset(5120, 1440, "32:9 super ultrawide"),
            new ResolutionPreset(7680, 4320, "8K"));

    static Optional<ResolutionPreset> of(int width, int height) {
        return ALL.stream().filter(p -> p.width == width && p.height == height).findFirst();
    }

    @Override
    public String toString() {
        return width + " × " + height + " (" + name + ")";
    }
}
