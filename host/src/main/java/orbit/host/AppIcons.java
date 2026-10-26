package orbit.host;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;

/**
 * Small copies of Sunshine's app covers for the clients' app list ({@code Message.AppList.icons}): a cover is box art,
 * usually 600×800, far more than a row of the list needs. Each is made once per version of its file. Thread-safe.
 */
final class AppIcons {

    private static final Logger LOG = Logger.getLogger(AppIcons.class.getName());

    /** Fits in this box: sharp in a list row even on a 4K TV, a few kilobytes as PNG. */
    static final int MAX_WIDTH = 150;
    static final int MAX_HEIGHT = 200;

    private record Made(FileTime modified, String png) {
    }

    private static final Map<Path, Made> MADE = new ConcurrentHashMap<>();

    private AppIcons() {
    }

    /** The cover as a small PNG in Base64, or null when there's none or it can't be read. */
    static String icon(Optional<Path> cover) {
        if (cover.isEmpty()) {
            return null;
        }
        Path file = cover.get();
        try {
            FileTime modified = Files.getLastModifiedTime(file);
            Made made = MADE.get(file);
            if (made == null || !made.modified().equals(modified)) {
                BufferedImage image = ImageIO.read(file.toFile());
                made = new Made(modified, image != null ? png(thumbnail(image)) : null);
                MADE.put(file, made);
            }
            return made.png();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "cannot make an icon of " + file, e);
            return null;
        }
    }

    /** {@code image} scaled down to fit {@link #MAX_WIDTH}×{@link #MAX_HEIGHT}, never up. */
    static BufferedImage thumbnail(BufferedImage image) {
        double scale = Math.min(1, Math.min((double) MAX_WIDTH / image.getWidth(),
                (double) MAX_HEIGHT / image.getHeight()));
        BufferedImage current = image;
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        // Halving in steps keeps detail that a single bilinear step from 800 rows to 200 would alias.
        do {
            int w = Math.max(width, current.getWidth() / 2);
            int h = Math.max(height, current.getHeight() / 2);
            BufferedImage next = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(current, 0, 0, w, h, null);
            g.dispose();
            current = next;
        } while (current.getWidth() > width || current.getHeight() > height);
        return current;
    }

    private static String png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
