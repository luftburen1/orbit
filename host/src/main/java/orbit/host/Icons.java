package orbit.host;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Function;
import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.UIManager;
import orbit.protocol.Logo;
import orbit.protocol.RemoteIcon;
import orbit.tray.TrayIcons;

/** Icons drawn in code so no image assets are needed. The logo itself is {@link Logo}, shared with the agent. */
public final class Icons {

    private static final Color PLAY = TrayIcons.PLAY;
    private static final Color STOP = new Color(0xD64545);
    private static final Color GOLD = new Color(0xF2B620);
    private static final Color GOLD_EDGE = new Color(0x9C6B00);

    private Icons() {
    }

    public static BufferedImage logo(int size) {
        return Logo.image(size);
    }

    /** How long one pulse of {@link #pulsingDot} takes. */
    static final long PULSE_NANOS = 1_600_000_000L;

    /**
     * A green dot whose opacity pulses with the clock, shown next to "Streaming". Each paint shows the current
     * phase, so repainting it regularly animates it.
     */
    static Icon pulsingDot(int size) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setComposite(AlphaComposite.SrcOver.derive(pulseAlpha(System.nanoTime())));
                g2.setColor(PLAY);
                double d = size * 0.6;
                g2.fill(new Ellipse2D.Double(x + (size - d) / 2, y + (size - d) / 2, d, d));
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    /** A steady green ring, the size of {@link #pulsingDot}'s dot, shown next to "Available". */
    static Icon readyRing(int size) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(PLAY);
                float stroke = size / 6f;
                g2.setStroke(new BasicStroke(stroke));
                double d = size * 0.6 - stroke;
                g2.draw(new Ellipse2D.Double(x + (size - d) / 2, y + (size - d) / 2, d, d));
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    /**
     * {@code base} in its normal colours, its opacity pulsing like {@link #pulsingDot}: a toolbar button whose action
     * is in progress (as its disabled icon). Each paint shows the current phase, so repainting it animates it.
     */
    static Icon pulsing(Icon base) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setComposite(AlphaComposite.SrcOver.derive(pulseAlpha(System.nanoTime())));
                // No component: shape icons then use their own colour instead of the disabled grey.
                base.paintIcon(null, g2, x, y);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return base.getIconWidth();
            }

            @Override
            public int getIconHeight() {
                return base.getIconHeight();
            }
        };
    }

    /** The dot's opacity at {@code nanos}: fully opaque at the start of each pulse, fading to 0.3 halfway. */
    static float pulseAlpha(long nanos) {
        double phase = Math.floorMod(nanos, PULSE_NANOS) / (double) PULSE_NANOS;
        return (float) (0.3 + 0.7 * (0.5 + 0.5 * Math.cos(2 * Math.PI * phase)));
    }

    static List<Image> windowIcons() {
        return List.of(logo(16), logo(32), logo(64), logo(128));
    }

    /** A "play" triangle, green when its button is enabled. */
    static Icon play(int size) {
        return shapeIcon(size, PLAY, n -> {
            double inset = n * 0.12;
            Path2D.Double triangle = new Path2D.Double();
            triangle.moveTo(inset * 1.5, inset);
            triangle.lineTo(n - inset, n / 2.0);
            triangle.lineTo(inset * 1.5, n - inset);
            triangle.closePath();
            return triangle;
        });
    }

    /** A "stop" square, red when its button is enabled. */
    static Icon stop(int size) {
        return shapeIcon(size, STOP, n -> {
            double inset = n * 0.2;
            return new RoundRectangle2D.Double(inset, inset, n - 2 * inset, n - 2 * inset, n * 0.12, n * 0.12);
        });
    }

    /** A settings cog in the theme's text colour. */
    static Icon cog(int size) {
        return shapeIcon(size, null, n -> {
            double c = n / 2.0;
            Area cog = new Area(new Ellipse2D.Double(c - n * 0.32, c - n * 0.32, n * 0.64, n * 0.64));
            int teeth = 8;
            for (int i = 0; i < teeth; i++) {
                Area tooth = new Area(new RoundRectangle2D.Double(c - n * 0.08, c - n * 0.46, n * 0.16, n * 0.2,
                        n * 0.05, n * 0.05));
                tooth.transform(AffineTransform.getRotateInstance(2 * Math.PI * i / teeth, c, c));
                cog.add(tooth);
            }
            cog.subtract(new Area(new Ellipse2D.Double(c - n * 0.13, c - n * 0.13, n * 0.26, n * 0.26)));
            return cog;
        });
    }

    /** A trash can in the theme's text colour. */
    static Icon trash(int size) {
        return shapeIcon(size, null, n -> {
            Area can = new Area(
                    new RoundRectangle2D.Double(n * 0.36, n * 0.06, n * 0.28, n * 0.14, n * 0.06, n * 0.06));
            can.subtract(new Area(new Rectangle2D.Double(n * 0.42, n * 0.12, n * 0.16, n * 0.1)));
            can.add(new Area(new RoundRectangle2D.Double(n * 0.12, n * 0.18, n * 0.76, n * 0.1, n * 0.05, n * 0.05)));
            Path2D.Double body = new Path2D.Double();
            body.moveTo(n * 0.2, n * 0.32);
            body.lineTo(n * 0.8, n * 0.32);
            body.lineTo(n * 0.74, n * 0.94);
            body.lineTo(n * 0.26, n * 0.94);
            body.closePath();
            Area bodyArea = new Area(body);
            for (double x : new double[] {0.34, 0.47, 0.6}) {
                bodyArea.subtract(new Area(new RoundRectangle2D.Double(n * x, n * 0.42, n * 0.06, n * 0.42,
                        n * 0.06, n * 0.06)));
            }
            can.add(bodyArea);
            return can;
        });
    }

    /** Three bars, for the main menu, in the theme's text colour. */
    static Icon menu(int size) {
        return shapeIcon(size, null, n -> {
            Area bars = new Area();
            for (double y : new double[] {0.2, 0.44, 0.68}) {
                bars.add(new Area(new RoundRectangle2D.Double(n * 0.14, n * y, n * 0.72, n * 0.12, n * 0.12,
                        n * 0.12)));
            }
            return bars;
        });
    }

    /** An "i" in a ring, for the About box, in the theme's text colour. */
    static Icon info(int size) {
        return shapeIcon(size, null, n -> {
            Area info = new Area(new Ellipse2D.Double(n * 0.06, n * 0.06, n * 0.88, n * 0.88));
            info.subtract(new Area(new Ellipse2D.Double(n * 0.15, n * 0.15, n * 0.7, n * 0.7)));
            info.add(new Area(new Ellipse2D.Double(n * 0.43, n * 0.24, n * 0.14, n * 0.14)));
            info.add(new Area(new RoundRectangle2D.Double(n * 0.44, n * 0.43, n * 0.12, n * 0.33, n * 0.06,
                    n * 0.06)));
            return info;
        });
    }

    /** A check mark, for approving, in the theme's text colour. */
    static Icon check(int size) {
        return shapeIcon(size, null, n -> {
            Path2D.Double tick = new Path2D.Double();
            tick.moveTo(n * 0.16, n * 0.52);
            tick.lineTo(n * 0.4, n * 0.76);
            tick.lineTo(n * 0.86, n * 0.26);
            return new BasicStroke((float) (n * 0.14), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    .createStrokedShape(tick);
        });
    }

    /** Two chain links, for pairing, in the theme's text colour. */
    static Icon link(int size) {
        return shapeIcon(size, null, n -> {
            Area chain = new Area();
            for (double offset : new double[] {-0.17, 0.17}) {
                double cx = n / 2.0 + n * offset;
                double w = n * 0.56;
                double h = n * 0.32;
                double t = n * 0.1;
                Area ring = new Area(new RoundRectangle2D.Double(cx - w / 2, n / 2.0 - h / 2, w, h, h, h));
                ring.subtract(new Area(new RoundRectangle2D.Double(cx - w / 2 + t, n / 2.0 - h / 2 + t, w - 2 * t,
                        h - 2 * t, h - 2 * t, h - 2 * t)));
                chain.add(ring);
            }
            chain.transform(AffineTransform.getRotateInstance(-Math.PI / 4, n / 2.0, n / 2.0));
            return chain;
        });
    }

    /** The link icon pulled apart: unpairing. */
    static Icon unlink(int size) {
        return shapeIcon(size, null, n -> {
            Area chain = new Area();
            for (double offset : new double[] {-0.24, 0.24}) {
                double cx = n / 2.0 + n * offset;
                double w = n * 0.42;
                double h = n * 0.32;
                double t = n * 0.1;
                Area ring = new Area(new RoundRectangle2D.Double(cx - w / 2, n / 2.0 - h / 2, w, h, h, h));
                ring.subtract(new Area(new RoundRectangle2D.Double(cx - w / 2 + t, n / 2.0 - h / 2 + t, w - 2 * t,
                        h - 2 * t, h - 2 * t, h - 2 * t)));
                chain.add(ring);
            }
            chain.transform(AffineTransform.getRotateInstance(-Math.PI / 4, n / 2.0, n / 2.0));
            return chain;
        });
    }

    /** A screen on a stand, in the theme's text colour. */
    static Icon monitor(int size) {
        return shapeIcon(size, null, n -> {
            Area screen = new Area(
                    new RoundRectangle2D.Double(n * 0.06, n * 0.12, n * 0.88, n * 0.6, n * 0.1, n * 0.1));
            screen.subtract(new Area(new Rectangle2D.Double(n * 0.15, n * 0.21, n * 0.7, n * 0.42)));
            screen.add(new Area(new Rectangle2D.Double(n * 0.44, n * 0.72, n * 0.12, n * 0.12)));
            screen.add(new Area(new RoundRectangle2D.Double(n * 0.26, n * 0.82, n * 0.48, n * 0.08, n * 0.04,
                    n * 0.04)));
            return screen;
        });
    }

    /**
     * A gold crown, on the client that leads the stream. Gold in both themes, like the play and stop colours, with a
     * darker edge so it holds up on a light background.
     */
    static Icon crown(int size) {
        Shape shape = crownShape(size);
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g2.translate(x, y);
                boolean disabled = c != null && !c.isEnabled();
                g2.setColor(disabled ? themeColor("Label.disabledForeground", Color.GRAY) : GOLD);
                g2.fill(shape);
                if (!disabled) {
                    g2.setColor(GOLD_EDGE);
                    g2.setStroke(new BasicStroke(Math.max(1f, size / 16f), BasicStroke.CAP_ROUND,
                            BasicStroke.JOIN_ROUND));
                    g2.draw(shape);
                }
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    /** {@link #crown} as a PNG of {@code size}×{@code size} pixels, for the tray menu. */
    static byte[] crownPng(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        crown(size).paintIcon(null, g, 0, 0);
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", png);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return png.toByteArray();
    }

    /** Three points with a ball on each, above a band, in an {@code n}×{@code n} box. */
    private static Shape crownShape(double n) {
        Path2D points = new Path2D.Double();
        points.moveTo(n * 0.16, n * 0.66);
        points.lineTo(n * 0.10, n * 0.32);
        points.lineTo(n * 0.32, n * 0.48);
        points.lineTo(n * 0.50, n * 0.22);
        points.lineTo(n * 0.68, n * 0.48);
        points.lineTo(n * 0.90, n * 0.32);
        points.lineTo(n * 0.84, n * 0.66);
        points.closePath();
        Area crown = new Area(points);
        double r = n * 0.075;
        for (double[] tip : new double[][] {{0.10, 0.29}, {0.50, 0.18}, {0.90, 0.29}}) {
            crown.add(new Area(new Ellipse2D.Double(n * tip[0] - r, n * tip[1] - r, 2 * r, 2 * r)));
        }
        crown.add(new Area(new RoundRectangle2D.Double(n * 0.16, n * 0.71, n * 0.68, n * 0.13, n * 0.05, n * 0.05)));
        return crown;
    }

    /** A TV remote ({@link RemoteIcon}), in the theme's text colour. */
    static Icon remote(int size) {
        return shapeIcon(size, null, RemoteIcon::shape);
    }

    /**
     * An icon that fills a shape (laid out in a {@code size}×{@code size} box) in {@code color}, or the theme's text
     * colour if null, and in the theme's disabled colour when its button is disabled.
     */
    private static Icon shapeIcon(int size, Color color, Function<Double, Shape> shape) {
        Shape outline = shape.apply((double) size);
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c != null && !c.isEnabled() ? themeColor("Label.disabledForeground", Color.GRAY)
                        : color != null ? color : themeColor("Button.foreground", Color.DARK_GRAY));
                g2.translate(x, y);
                g2.fill(outline);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    private static Color themeColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }
}
