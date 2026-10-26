package orbit.protocol;

import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/**
 * A TV remote, drawn in code: the host's client list and the agent's idle screen show it for clients that read the
 * TV's remote over HDMI-CEC. One filled shape, the buttons cut out, so it takes any single colour.
 */
public final class RemoteIcon {

    private RemoteIcon() {
    }

    /** The remote standing upright in the {@code size}×{@code size} square at (0, 0). */
    public static Shape shape(double size) {
        double s = size;
        Area remote = new Area(new RoundRectangle2D.Double(s * 0.27, s * 0.03, s * 0.46, s * 0.94, s * 0.2, s * 0.2));
        // The arrows' ring with OK in the middle.
        remote.subtract(new Area(circle(s * 0.5, s * 0.31, s * 0.15)));
        remote.add(new Area(circle(s * 0.5, s * 0.31, s * 0.065)));
        // Two rows of buttons below.
        for (double y : new double[] {0.6, 0.78}) {
            remote.subtract(new Area(circle(s * 0.41, s * y, s * 0.055)));
            remote.subtract(new Area(circle(s * 0.59, s * y, s * 0.055)));
        }
        return remote;
    }

    private static Ellipse2D circle(double cx, double cy, double r) {
        return new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
    }
}
