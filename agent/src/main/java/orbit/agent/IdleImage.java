package orbit.agent;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;

/**
 * The idle screen's own picture ({@code idleImage} in agent.json), read when first needed and again after the file
 * changes. Used from one thread only: the window's event thread or the framebuffer's painter.
 */
final class IdleImage {

    private static final Logger LOG = Logger.getLogger(IdleImage.class.getName());

    private final Path path;
    private BufferedImage image;
    /** Null before the first read; zero after a failed one. */
    private FileTime modified;

    /** @param path the picture, or null for none */
    IdleImage(Path path) {
        this.path = path;
    }

    /** The picture as last read, or null when there is none or it can't be read. */
    BufferedImage image() {
        return image;
    }

    /** Reads the picture if it's new or its file changed; returns true if {@link #image()} changed. */
    boolean reloadIfChanged() {
        if (path == null) {
            return false;
        }
        try {
            FileTime current = Files.getLastModifiedTime(path);
            if (current.equals(modified)) {
                return false;
            }
            modified = current;
            image = ImageIO.read(path.toFile());
            if (image == null) {
                LOG.warning(() -> path + " is not a supported image (use PNG, JPEG, GIF or BMP)");
            }
            return true;
        } catch (IOException e) {
            // Logged once, not on every redraw while the file stays missing.
            boolean changed = image != null || modified == null;
            if (changed) {
                LOG.log(Level.WARNING, "cannot read idle image " + path, e);
            }
            modified = FileTime.fromMillis(0);
            image = null;
            return changed;
        }
    }
}
