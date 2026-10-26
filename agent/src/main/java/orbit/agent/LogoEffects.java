package orbit.agent;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import orbit.protocol.Dither;
import orbit.protocol.Logo;

/**
 * The Orbit logo on the idle screen, optionally animated: a softly pulsing glow, a glint orbiting the ring and
 * sparkles twinkling around it. Everything is drawn inside {@link #region}, so an animation frame only needs that
 * square redrawn.
 */
final class LogoEffects {

    /** Frames per second of the animation. */
    static final int FPS = 15;

    private static final double RING_RADIUS = 0.42;
    private static final Color GLOW = new Color(0x4D7DF0);
    private static final Color SPARKLE = new Color(0xE4ECFF);
    private static final List<Sparkle> SPARKLES = sparkles();

    /** One twinkling star, placed around the logo; sizes and distances are fractions of the logo's size. */
    private record Sparkle(double angle, double distance, double size, double period, double phase, Color color) {
    }

    private LogoEffects() {
    }

    /** Where the logo goes: a little above the middle, a third of the screen's shorter side. */
    static Rectangle2D.Double logo(int width, int height) {
        double size = Math.min(width, height) * 0.3;
        return new Rectangle2D.Double((width - size) / 2, (height - size) / 2 - height * 0.05, size, size);
    }

    /** The square the logo and its effects are drawn in, clipped to the screen. */
    static Rectangle region(int width, int height) {
        Rectangle2D.Double logo = logo(width, height);
        double half = logo.width * 0.8;
        Rectangle square = new Rectangle((int) Math.floor(logo.getCenterX() - half),
                (int) Math.floor(logo.getCenterY() - half), (int) Math.ceil(2 * half), (int) Math.ceil(2 * half));
        return square.intersection(new Rectangle(0, 0, width, height));
    }

    /**
     * Draws the logo, and with {@code animate} its effects at {@code seconds}, over {@code area}: a
     * {@code TYPE_INT_RGB} image holding the background of {@link #region}.
     */
    static void paint(BufferedImage area, Rectangle region, int width, int height, double seconds, boolean animate) {
        Rectangle2D.Double logo = logo(width, height);
        double size = logo.width;
        double cx = logo.getCenterX() - region.x;
        double cy = logo.getCenterY() - region.y;
        if (animate) {
            glow(area, region, cx, cy, size, seconds);
        }
        Graphics2D g = area.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Logo.paint(g, logo.x - region.x, logo.y - region.y, size);
            if (animate) {
                glint(g, cx, cy, size, seconds);
                for (Sparkle s : SPARKLES) {
                    sparkle(g, s, cx, cy, size, seconds);
                }
            }
        } finally {
            g.dispose();
        }
    }

    /**
     * A blue glow around the ring and the planet, pulsing slowly. Added per pixel and dithered, because a faint glow
     * on a dark background would otherwise show as rings.
     */
    private static void glow(BufferedImage area, Rectangle region, double cx, double cy, double size, double t) {
        int[] pixels = ((DataBufferInt) area.getRaster().getDataBuffer()).getData();
        float[] shape = glowShape(area.getWidth(), area.getHeight(), cx, cy, size);
        double pulse = 0.85 + 0.15 * Math.sin(2 * Math.PI * t / 5);
        double gr = GLOW.getRed() * 0.32 * pulse;
        double gg = GLOW.getGreen() * 0.32 * pulse;
        double gb = GLOW.getBlue() * 0.32 * pulse;
        int w = area.getWidth();
        for (int i = 0; i < pixels.length; i++) {
            float intensity = shape[i];
            if (intensity == 0) {
                continue;
            }
            int rgb = pixels[i];
            double threshold = Dither.threshold(region.x + i % w, region.y + i / w);
            int r = add((rgb >> 16) & 0xFF, gr * intensity, threshold);
            int g = add((rgb >> 8) & 0xFF, gg * intensity, threshold);
            int b = add(rgb & 0xFF, gb * intensity, threshold);
            pixels[i] = (r << 16) | (g << 8) | b;
        }
    }

    private record GlowKey(int width, int height, double cx, double cy, double size) {
    }

    private static GlowKey glowKey;
    private static float[] glowShape;

    /**
     * How strong the glow is at each pixel of the region, before pulsing: brightest along the ring, softer over the
     * planet, fading to nothing before the region's edge so no square shows. The same for every frame, so it's
     * computed once.
     */
    private static synchronized float[] glowShape(int w, int h, double cx, double cy, double size) {
        GlowKey key = new GlowKey(w, h, cx, cy, size);
        if (key.equals(glowKey)) {
            return glowShape;
        }
        float[] shape = new float[w * h];
        for (int y = 0; y < h; y++) {
            double dy = (y + 0.5 - cy) / size;
            for (int x = 0; x < w; x++) {
                double dx = (x + 0.5 - cx) / size;
                double d = Math.sqrt(dx * dx + dy * dy);
                double ring = (d - RING_RADIUS) / 0.09;
                double intensity = 0.9 * Math.exp(-ring * ring)
                        + 0.6 * Math.exp(-(d / 0.3) * (d / 0.3))
                        + 0.35 * Math.exp(-(d / 0.5) * (d / 0.5));
                double fade = Math.max(0, Math.min(1, (0.78 - d) / 0.28));
                double value = intensity * fade * fade;
                shape[y * w + x] = value < 0.004 ? 0 : (float) value;
            }
        }
        glowKey = key;
        glowShape = shape;
        return shape;
    }

    private static int add(int channel, double amount, double threshold) {
        return Math.min(255, (int) Math.floor(channel + amount + threshold));
    }

    /** A small light travelling along the ring, once every 12 s. */
    private static void glint(Graphics2D g, double cx, double cy, double size, double t) {
        double angle = 2 * Math.PI * t / 12 - Math.PI / 2;
        double radius = RING_RADIUS * size;
        for (int i = 5; i >= 0; i--) {
            double a = angle - i * 0.06;
            double alpha = i == 0 ? 0.95 : 0.4 * (1 - i / 6.0);
            double r = size * (i == 0 ? 0.075 : 0.045);
            softDot(g, cx + Math.cos(a) * radius, cy + Math.sin(a) * radius, r, new Color(255, 255, 255), alpha);
        }
    }

    private static void sparkle(Graphics2D g, Sparkle s, double cx, double cy, double size, double t) {
        double wave = Math.sin(2 * Math.PI * t / s.period() + s.phase());
        double brightness = wave <= 0 ? 0 : wave * wave * wave;
        if (brightness < 0.02) {
            return;
        }
        double x = cx + Math.cos(s.angle()) * s.distance() * size;
        double y = cy + Math.sin(s.angle()) * s.distance() * size;
        double length = s.size() * size * (0.55 + 0.45 * brightness);
        softDot(g, x, y, length * 0.9, s.color(), 0.45 * brightness);
        double thin = length * 0.16;
        Path2D.Double star = new Path2D.Double();
        star.moveTo(x, y - length);
        star.lineTo(x + thin, y - thin);
        star.lineTo(x + length, y);
        star.lineTo(x + thin, y + thin);
        star.lineTo(x, y + length);
        star.lineTo(x - thin, y + thin);
        star.lineTo(x - length, y);
        star.lineTo(x - thin, y - thin);
        star.closePath();
        g.setColor(withAlpha(s.color(), brightness));
        g.fill(star);
    }

    /** A dot that fades out from its centre. */
    private static void softDot(Graphics2D g, double x, double y, double radius, Color color, double alpha) {
        if (radius < 0.5) {
            return;
        }
        g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) radius, new float[] {0f, 0.35f, 1f},
                new Color[] {withAlpha(color, alpha), withAlpha(color, alpha * 0.45), withAlpha(color, 0)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fill(new Ellipse2D.Double(x - radius, y - radius, 2 * radius, 2 * radius));
    }

    private static Color withAlpha(Color c, double alpha) {
        int a = (int) Math.round(255 * Math.max(0, Math.min(1, alpha)));
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    /** The same sparkles every time: spread around the logo, outside the ring, each with its own rhythm. */
    private static List<Sparkle> sparkles() {
        Random random = new Random(7);
        List<Sparkle> list = new ArrayList<>();
        int count = 12;
        for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * (i + random.nextDouble() * 0.7) / count;
            list.add(new Sparkle(angle, 0.54 + random.nextDouble() * 0.18, 0.03 + random.nextDouble() * 0.035,
                    2.4 + random.nextDouble() * 2.4, random.nextDouble() * 2 * Math.PI,
                    random.nextDouble() < 0.25 ? Logo.MOON : SPARKLE));
        }
        return list;
    }
}
