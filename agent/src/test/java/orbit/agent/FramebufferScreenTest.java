package orbit.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FramebufferScreenTest {

    @TempDir
    Path dir;

    private Path sysfs(String bpp, String stride, String modes, String virtualSize) throws Exception {
        Path fb0 = Files.createDirectories(dir.resolve("fb0"));
        Files.writeString(fb0.resolve("bits_per_pixel"), bpp + "\n");
        Files.writeString(fb0.resolve("stride"), stride + "\n");
        if (modes != null) {
            Files.writeString(fb0.resolve("modes"), modes + "\n");
        }
        Files.writeString(fb0.resolve("virtual_size"), virtualSize + "\n");
        return fb0;
    }

    @Test
    void readsGeometryFromVisibleMode() throws Exception {
        // virtual_size is twice as tall (page flipping); the visible mode wins.
        assertEquals(Optional.of(new FramebufferScreen.Geometry(1920, 1080, 32, 7680)),
                FramebufferScreen.geometry(sysfs("32", "7680", "U:1920x1080p-60", "1920,2160")));
    }

    @Test
    void fallsBackToVirtualSize() throws Exception {
        assertEquals(Optional.of(new FramebufferScreen.Geometry(1280, 720, 16, 2560)),
                FramebufferScreen.geometry(sysfs("16", "2560", null, "1280,720")));
    }

    @Test
    void rejectsUnsupportedFormats() throws Exception {
        assertTrue(FramebufferScreen.geometry(sysfs("24", "5760", "U:1920x1080p-60", "1920,1080")).isEmpty());
        assertTrue(FramebufferScreen.geometry(dir.resolve("missing")).isEmpty());
    }

    private static BufferedImage pixel(Color c) {
        BufferedImage img = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, c.getRGB());
        img.setRGB(1, 0, Color.WHITE.getRGB());
        return img;
    }

    @Test
    void encodesXrgb8888WithStride() {
        ByteBuffer buf = FramebufferScreen.encode(pixel(new Color(0x11, 0x22, 0x33)),
                new FramebufferScreen.Geometry(2, 1, 32, 12));
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        assertArrayEquals(new byte[] {0x33, 0x22, 0x11, (byte) 0xFF, -1, -1, -1, -1, 0, 0, 0, 0}, bytes,
                "B G R X per pixel, padded to the stride");
    }

    @Test
    void encodesRgb565() {
        ByteBuffer buf = FramebufferScreen.encode(pixel(Color.RED), new FramebufferScreen.Geometry(2, 1, 16, 4));
        assertEquals((byte) 0x00, buf.get(0));
        assertEquals((byte) 0xF8, buf.get(1), "red is the top 5 bits of the little-endian word");
        assertEquals((byte) 0xFF, buf.get(2));
        assertEquals((byte) 0xFF, buf.get(3));
    }

    @Test
    void drawsPictureIntoFramebuffer() throws Exception {
        BufferedImage green = new BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = green.createGraphics();
        g.setColor(Color.GREEN);
        g.fillRect(0, 0, 64, 36);
        g.dispose();
        Path image = dir.resolve("idle.png");
        ImageIO.write(green, "png", image.toFile());
        Path device = dir.resolve("fb0.raw");
        Files.write(device, new byte[320 * 4 * 180]);

        FramebufferScreen screen = new FramebufferScreen(device,
                new FramebufferScreen.Geometry(320, 180, 32, 320 * 4), image, "livingroom", "orbit-agent test");
        screen.show("Ready to stream");
        screen.awaitDrawn();

        byte[] fb = Files.readAllBytes(device);
        int topRight = (5 * 320 + 315) * 4;
        assertArrayEquals(new byte[] {0, (byte) 0xFF, 0, (byte) 0xFF}, Arrays.copyOfRange(fb, topRight, topRight + 4),
                "picture fills the framebuffer");
    }

    @Test
    void findsTheConsoleFromAnyStandardStream(@org.junit.jupiter.api.io.TempDir Path fds) throws Exception {
        // Like the tty1 setup: output and errors go to the log file, input is still the console.
        Files.createSymbolicLink(fds.resolve("0"), Path.of("/dev/tty1"));
        Files.createSymbolicLink(fds.resolve("1"), Path.of("/home/user/.local/state/orbit/agent.log"));
        Files.createSymbolicLink(fds.resolve("2"), Path.of("/home/user/.local/state/orbit/agent.log"));
        assertEquals(java.util.Optional.of(Path.of("/dev/tty1")), FramebufferScreen.console(fds));
    }

    @Test
    void noConsoleWhenStartedFromSshOrAService(@org.junit.jupiter.api.io.TempDir Path fds) throws Exception {
        Files.createSymbolicLink(fds.resolve("0"), Path.of("/dev/null"));
        Files.createSymbolicLink(fds.resolve("1"), Path.of("/dev/pts/3"));
        Files.createSymbolicLink(fds.resolve("2"), Path.of("/dev/tty"));
        assertEquals(java.util.Optional.empty(), FramebufferScreen.console(fds));
    }

    @Test
    void hidesTheCursorAgainAfterEachStream(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path device = dir.resolve("fb0.raw");
        Files.write(device, new byte[64 * 4 * 36]);
        Path console = dir.resolve("tty1");
        Files.createFile(console);
        FramebufferScreen screen = new FramebufferScreen(device, new FramebufferScreen.Geometry(64, 36, 32, 64 * 4),
                null, "livingroom", "orbit-agent test", java.util.Optional.of(console));
        String hide = "\033[?25l";

        screen.show("Ready to stream");
        screen.show("Ready to stream");
        screen.awaitDrawn();
        assertEquals(hide, Files.readString(console), "hidden once, not on every redraw");

        screen.hide();          // a stream runs; moonlight-qt shows the cursor again when it exits
        screen.show("Ready to stream");
        screen.awaitDrawn();
        assertEquals(hide + hide, Files.readString(console), "hidden again when the idle screen comes back");
    }

    @Test
    void graphicsModeFailsQuietlyOnSomethingThatIsntAConsole(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path notConsole = Files.createFile(dir.resolve("tty1"));
        org.junit.jupiter.api.Assertions.assertFalse(ConsoleMode.setGraphics(notConsole, true));
        org.junit.jupiter.api.Assertions.assertFalse(ConsoleMode.setGraphics(dir.resolve("missing"), true));
    }

    @Test
    void animatesOnlyTheLogoRegionAndStopsWhenHidden(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        int w = 320, h = 180;
        Path device = dir.resolve("fb0.raw");
        Files.write(device, new byte[w * 4 * h]);
        FramebufferScreen screen = new FramebufferScreen(device, new FramebufferScreen.Geometry(w, h, 32, w * 4),
                null, "livingroom", "orbit-agent test", java.util.Optional.empty(), true);
        screen.show("Ready to stream");
        screen.awaitDrawn();
        byte[] first = Files.readAllBytes(device);
        Thread.sleep(700);
        screen.awaitDrawn();
        byte[] later = Files.readAllBytes(device);

        java.awt.Rectangle region = LogoEffects.region(w, h);
        boolean regionChanged = false;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                boolean same = Arrays.equals(Arrays.copyOfRange(first, i, i + 4), Arrays.copyOfRange(later, i, i + 4));
                if (region.contains(x, y)) {
                    regionChanged |= !same;
                } else {
                    assertTrue(same, "outside the logo's region nothing is redrawn, at " + x + "," + y);
                }
            }
        }
        assertTrue(regionChanged, "the logo's region animates");

        screen.hide();
        screen.awaitDrawn();
        byte[] hidden = Files.readAllBytes(device);
        Thread.sleep(400);
        screen.awaitDrawn();
        assertArrayEquals(hidden, Files.readAllBytes(device), "nothing is written while Moonlight streams");
    }
}
