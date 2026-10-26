package orbit.host;

/**
 * Recommended stream bitrates, following the defaults Moonlight picks when you change resolution or frame rate: the
 * rate grows with the pixel count (interpolated between common resolutions) and with FPS, which counts fully up to
 * 60 and less beyond that.
 */
final class BitrateAdvisor {

    /** Pixel counts and their bitrate factor (Mbps at 30 fps). */
    private static final int[][] TABLE = {
            {640 * 360, 1},
            {854 * 480, 2},
            {1280 * 720, 5},
            {1920 * 1080, 10},
            {2560 * 1440, 20},
            {3840 * 2160, 40},
    };

    /** Moonlight's largest bitrate setting. */
    static final int MAX_KBPS = 150_000;

    private BitrateAdvisor() {
    }

    static int recommendedKbps(int width, int height, int fps) {
        double frameRateFactor = (fps <= 60 ? fps : Math.sqrt(fps / 60.0) * 60) / 30.0;
        long mbps = Math.round(resolutionFactor((long) width * height) * frameRateFactor);
        return (int) Math.min(MAX_KBPS, Math.max(1, mbps) * 1000);
    }

    private static double resolutionFactor(long pixels) {
        if (pixels <= TABLE[0][0]) {
            return TABLE[0][1];
        }
        for (int i = 1; i < TABLE.length; i++) {
            if (pixels <= TABLE[i][0]) {
                double share = (double) (pixels - TABLE[i - 1][0]) / (TABLE[i][0] - TABLE[i - 1][0]);
                return TABLE[i - 1][1] + share * (TABLE[i][1] - TABLE[i - 1][1]);
            }
        }
        // Beyond 4K: keep the 4K rate per pixel.
        int[] last = TABLE[TABLE.length - 1];
        return last[1] * (double) pixels / last[0];
    }

    /** "20 Mbps", "4.5 Mbps". */
    static String format(int kbps) {
        return kbps % 1000 == 0 ? kbps / 1000 + " Mbps" : String.format("%.1f Mbps", kbps / 1000.0);
    }
}
