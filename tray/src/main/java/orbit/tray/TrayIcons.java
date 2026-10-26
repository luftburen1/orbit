package orbit.tray;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import orbit.protocol.Logo;

/** The tray icon, drawn in code from the {@link Logo}; the same for the host and the agent. */
public final class TrayIcons {

    /** The green of "play": the streaming icon here, and the host's Start button and streaming dot. */
    public static final Color PLAY = new Color(0x2E9E5B);

    private TrayIcons() {
    }

    /**
     * The logo, whose planet becomes a green "play" button while something streams (just green below 20 px). The
     * planet is cleared first so no blue shows at its anti-aliased edge.
     */
    public static BufferedImage trayIcon(int size, boolean streaming) {
        BufferedImage img = Logo.image(size);
        if (!streaming) {
            return img;
        }
        // The planet's square in Logo.paint.
        double d = size * 0.44;
        double x = size * 0.28;
        double y = size * 0.28;
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setComposite(AlphaComposite.Clear);
        g.fill(new Ellipse2D.Double(x - 1, y - 1, d + 2, d + 2));
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(PLAY);
        g.fill(new Ellipse2D.Double(x, y, d, d));
        if (size < 20) {
            // Too small for the triangle to read; the green planet alone says it.
            g.dispose();
            return img;
        }
        // The triangle's centroid sits a little right of the circle's centre, so it looks centred.
        double t = d * 0.5;
        double cx = x + d / 2 + t * 0.08;
        double cy = y + d / 2;
        Path2D.Double triangle = new Path2D.Double();
        triangle.moveTo(cx - t * 0.4, cy - t / 2);
        triangle.lineTo(cx + t * 0.5, cy);
        triangle.lineTo(cx - t * 0.4, cy + t / 2);
        triangle.closePath();
        g.setColor(Color.WHITE);
        g.fill(triangle);
        g.dispose();
        return img;
    }
}
