package orbit.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import orbit.protocol.ClientState;
import org.junit.jupiter.api.Test;

class IdleScreenTest {

    @Test
    void statusLines() {
        assertEquals("Looking for the Orbit host…", OrbitAgent.idleText(OrbitAgent.Link.SEARCHING, null, ClientState.ERROR, false, false, null, true));
        assertEquals("Waiting for approval in Orbit", OrbitAgent.idleText(OrbitAgent.Link.PENDING, null, ClientState.ERROR, false, false, null, true));
        assertEquals("Not allowed by the Orbit host: invalid token",
                OrbitAgent.idleText(OrbitAgent.Link.REJECTED, "invalid token", ClientState.ERROR, false, false, null, true));
        assertEquals("Ready to stream", OrbitAgent.idleText(OrbitAgent.Link.APPROVED, null, ClientState.AVAILABLE, false, false, null, true));
        assertTrue(OrbitAgent.idleText(OrbitAgent.Link.APPROVED, null, ClientState.UNPAIRED, false, false, null, true).contains("Pair"));
        assertEquals("Moonlight isn't installed. Install moonlight-qt on this client",
                OrbitAgent.idleText(OrbitAgent.Link.APPROVED, null, ClientState.ERROR, true, false, null, true));
        assertEquals("Moonlight isn't installed. Install moonlight-qt on this client",
                OrbitAgent.idleText(OrbitAgent.Link.SEARCHING, null, ClientState.ERROR, true, false, null, true),
                "the missing Moonlight is the first thing to fix");
        assertEquals("Failed to connect to gamingpc",
                OrbitAgent.idleText(OrbitAgent.Link.APPROVED, null, ClientState.ERROR, false, false,
                        "Failed to connect to gamingpc", true), "Moonlight's own words when it gave some");
        assertEquals("Sunshine isn't running on the host",
                OrbitAgent.idleText(OrbitAgent.Link.APPROVED, null, ClientState.ERROR, false, true, null, true));
        assertEquals("Looking for the Orbit host…",
                OrbitAgent.idleText(OrbitAgent.Link.SEARCHING, null, ClientState.ERROR, false, true, null, true),
                "Sunshine only matters once connected to the host");
    }

    private static BufferedImage render(int w, int h, BufferedImage picture, String status) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        IdleScreen.paint(g, w, h, picture, "livingroom", status, IdleScreen.versionLine("Moonlight 6.1.0"));
        g.dispose();
        return out;
    }

    @Test
    void pictureCoversTheScreen() {
        // A 4:3 red picture with a blue centre, on a 16:9 screen: no black bars, centre stays centred.
        BufferedImage picture = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = picture.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 400, 300);
        g.setColor(Color.BLUE);
        g.fillRect(190, 140, 20, 20);
        g.dispose();

        BufferedImage screen = render(1920, 1080, picture, "Ready to stream");
        assertEquals(Color.BLUE.getRGB(), screen.getRGB(960, 540));
        assertEquals(Color.RED.getRGB(), screen.getRGB(5, 5), "top-left corner is covered");
        assertEquals(Color.RED.getRGB(), screen.getRGB(1914, 5), "top-right corner is covered");
    }

    @Test
    void captionIsDrawnBottomLeftWithoutPicture() {
        BufferedImage screen = render(1280, 720, null, "Looking for the Orbit host…");
        int top = screen.getRGB(640, 20);
        assertNotEquals(Color.BLACK.getRGB(), top, "gradient background");
        boolean white = false;
        for (int x = 40; x < 400 && !white; x++) {
            for (int y = 560; y < 690 && !white; y++) {
                white = (screen.getRGB(x, y) & 0xFFFFFF) == 0xFFFFFF;
            }
        }
        assertTrue(white, "caption text in the bottom-left corner");
    }

    /** Writes previews for a manual look when -Dorbit.idlePreview=<dir> is set. */
    @Test
    void writePreviews() throws Exception {
        String dir = System.getProperty("orbit.idlePreview");
        if (dir == null) {
            return;
        }
        BufferedImage picture = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = picture.createGraphics();
        g.setPaint(new java.awt.GradientPaint(0, 0, new Color(0xF2A65A), 1600, 900, new Color(0x5B3F8C)));
        g.fillRect(0, 0, 1600, 900);
        g.dispose();
        ImageIO.write(render(1280, 720, picture, "Ready to stream"), "png", Path.of(dir, "idle-image.png").toFile());
        ImageIO.write(render(1280, 720, null, "Looking for the Orbit host…"), "png",
                Path.of(dir, "idle-plain.png").toFile());
    }

    @Test
    void versionComesFromTheBuildAndIsDrawnBottomRight() {
        assertTrue(AgentInfo.VERSION.matches("\\d+\\.\\d+\\.\\d+.*"), AgentInfo.VERSION);
        BufferedImage screen = render(1280, 720, null, "Ready to stream");
        boolean text = false;
        for (int x = 1000; x < 1250 && !text; x++) {
            for (int y = 640; y < 700 && !text; y++) {
                Color c = new Color(screen.getRGB(x, y));
                text = c.getRed() > 140 && c.getGreen() > 140 && c.getBlue() > 140;
            }
        }
        assertTrue(text, "light version text in the bottom-right corner");
    }

    @Test
    void litRemoteIsGreenAndStaysInItsBox() {
        BufferedImage screen = render(1280, 720, null, "Ready to stream");
        BufferedImage before = render(1280, 720, null, "Ready to stream");
        Graphics2D g = screen.createGraphics();
        IdleScreen.paintRemote(g, 1280, 720, true);
        g.dispose();
        java.awt.Rectangle box = IdleScreen.remoteRegion(1280, 720);
        boolean green = false;
        for (int x = 0; x < 1280; x++) {
            for (int y = 0; y < 720; y++) {
                Color c = new Color(screen.getRGB(x, y));
                if (box.contains(x, y)) {
                    green |= c.getGreen() > 200 && c.getGreen() > c.getRed() && c.getGreen() - c.getBlue() > 40;
                } else {
                    assertEquals(before.getRGB(x, y), screen.getRGB(x, y), "outside the box at " + x + "," + y);
                }
            }
        }
        assertTrue(green, "a green remote");
    }

    @Test
    void tvRemoteGoesTopRightClearOfTheLogo() {
        BufferedImage screen = render(1280, 720, null, "Ready to stream");
        Graphics2D g = screen.createGraphics();
        IdleScreen.paintRemote(g, 1280, 720);
        g.dispose();
        java.awt.Rectangle drawn = null;
        for (int x = 0; x < 1280; x++) {
            for (int y = 0; y < 720; y++) {
                Color c = new Color(screen.getRGB(x, y));
                if (c.getRed() > 140 && c.getGreen() > 140 && c.getBlue() > 140 && y < 200) {
                    drawn = drawn == null ? new java.awt.Rectangle(x, y, 1, 1) : drawn.union(
                            new java.awt.Rectangle(x, y, 1, 1));
                }
            }
        }
        assertTrue(drawn != null && drawn.x > 1100 && drawn.y < 100, "light remote in the top-right corner: " + drawn);
        assertFalse(drawn.intersects(LogoEffects.region(1280, 720)), "logo frames never redraw it");
    }

    @Test
    void withoutPictureShowsTheOrbitLogo() {
        BufferedImage screen = render(1280, 720, null, "Ready to stream");
        // Planet centre: the logo is centred horizontally, a little above the middle.
        assertEquals(orbit.protocol.Logo.PLANET.getRGB(), screen.getRGB(640, 324));
    }

    @Test
    void versionLineNamesMoonlightOnce() {
        String agent = "orbit-agent " + AgentInfo.VERSION;
        assertEquals(agent + " · Moonlight 6.1.0", IdleScreen.versionLine("Moonlight 6.1.0"));
        assertEquals(agent + " · Moonlight 6.1.0", IdleScreen.versionLine("6.1.0"));
        assertEquals(agent, IdleScreen.versionLine(null));
    }

    @Test
    void nameStartsWithACapital() {
        assertEquals("Livingroom", IdleScreen.capitalized("livingroom"));
        assertEquals("Övervåning", IdleScreen.capitalized("övervåning"));
        assertEquals("Living-room", IdleScreen.capitalized("living-room"), "only the first letter changes");
        assertEquals("", IdleScreen.capitalized(""));
    }

    @Test
    void saysWhenStreamsAreTurnedOff() {
        assertEquals("Not allowing streams", OrbitAgent.idleText(OrbitAgent.Link.APPROVED, null,
                ClientState.AVAILABLE, false, false, null, false));
        assertEquals("Waiting for approval in Orbit", OrbitAgent.idleText(OrbitAgent.Link.PENDING, null,
                ClientState.AVAILABLE, false, false, null, false), "a bigger problem first");
    }
}
