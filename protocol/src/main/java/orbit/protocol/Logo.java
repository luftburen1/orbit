package orbit.protocol;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * The Orbit logo (a planet with a ring and a moon), drawn in code; shared by the host's icons and the agent's idle
 * screen.
 */
public final class Logo {

    public static final Color PLANET = new Color(0x3B6FD8);
    public static final Color MOON = new Color(0xF2B33D);

    private Logo() {
    }

    /** Draws the logo in the {@code size}×{@code size} square at ({@code x}, {@code y}). */
    public static void paint(Graphics2D g, double x, double y, double size) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.translate(x, y);
        double s = size;
        g2.setColor(PLANET);
        g2.fill(new Ellipse2D.Double(s * 0.28, s * 0.28, s * 0.44, s * 0.44));
        g2.setStroke(new BasicStroke((float) Math.max(1, s * 0.07)));
        g2.draw(new Ellipse2D.Double(s * 0.08, s * 0.08, s * 0.84, s * 0.84));
        g2.setColor(MOON);
        g2.fill(new Ellipse2D.Double(s * 0.70, s * 0.06, s * 0.24, s * 0.24));
        g2.dispose();
    }

    public static BufferedImage image(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        paint(g, 0, 0, size);
        g.dispose();
        return img;
    }

    /** Writes the logo as a PNG, for the build (the host's desktop icon): {@code <file> <size>}. */
    public static void main(String[] args) throws IOException {
        Path file = Path.of(args[0]);
        if (file.getParent() != null) {
            file.getParent().toFile().mkdirs();
        }
        ImageIO.write(image(Integer.parseInt(args[1])), "png", file.toFile());
    }
}
