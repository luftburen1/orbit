package orbit.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppIconsTest {

    @Test
    void coversShrinkToFitTheBoxKeepingTheirShape() {
        BufferedImage cover = AppIcons.thumbnail(new BufferedImage(600, 800, BufferedImage.TYPE_INT_RGB));
        assertEquals(150, cover.getWidth());
        assertEquals(200, cover.getHeight());
        BufferedImage wide = AppIcons.thumbnail(new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB));
        assertEquals(150, wide.getWidth());
        assertEquals(84, wide.getHeight());
        BufferedImage small = AppIcons.thumbnail(new BufferedImage(130, 180, BufferedImage.TYPE_INT_RGB));
        assertEquals(130, small.getWidth(), "never scaled up");
    }

    @Test
    void iconIsAPngInBase64(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cover.png");
        ImageIO.write(new BufferedImage(600, 800, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        String icon = AppIcons.icon(Optional.of(file));
        assertNotNull(icon);
        BufferedImage read = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(icon)));
        assertEquals(200, read.getHeight());

        Files.writeString(dir.resolve("broken.png"), "not a picture");
        assertNull(AppIcons.icon(Optional.of(dir.resolve("broken.png"))));
        assertNull(AppIcons.icon(Optional.of(dir.resolve("missing.png"))));
        assertNull(AppIcons.icon(Optional.empty()));
    }
}
