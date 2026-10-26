package orbit.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdleImageTest {

    @TempDir
    Path dir;

    @Test
    void noPictureNeverChanges() {
        IdleImage image = new IdleImage(null);
        assertFalse(image.reloadIfChanged());
        assertNull(image.image());
    }

    @Test
    void readsOnceThenAgainAfterTheFileChanges() throws Exception {
        Path file = dir.resolve("idle.png");
        ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        IdleImage image = new IdleImage(file);

        assertTrue(image.reloadIfChanged());
        assertEquals(4, image.image().getWidth());
        assertFalse(image.reloadIfChanged(), "unchanged file");

        ImageIO.write(new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 1000));
        assertTrue(image.reloadIfChanged());
        assertEquals(8, image.image().getWidth());
    }

    @Test
    void aMissingFileIsAChangeOnlyOnce() throws Exception {
        Path file = dir.resolve("idle.png");
        ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        IdleImage image = new IdleImage(file);
        assertTrue(image.reloadIfChanged());
        assertNotNull(image.image());

        Files.delete(file);
        assertTrue(image.reloadIfChanged(), "the picture went away");
        assertNull(image.image());
        assertFalse(image.reloadIfChanged(), "still missing: nothing to redraw");
    }

    @Test
    void aMissingFileAtStartIsOneChange() {
        IdleImage image = new IdleImage(dir.resolve("missing.png"));
        assertTrue(image.reloadIfChanged());
        assertFalse(image.reloadIfChanged());
        assertNull(image.image());
    }
}
