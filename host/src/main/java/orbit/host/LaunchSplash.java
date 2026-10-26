package orbit.host;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import orbit.protocol.Dither;
import orbit.protocol.Logo;

/**
 * A full-screen "Starting <app>" on Sunshine's monitor while a stream's app starts, so the stream shows the app's cover
 * instead of the bare desktop. The desktop's {@link HostDesktop.Windows} knows the window by its {@link #TITLE}: it
 * puts it fullscreen on Sunshine's monitor below every other window and out of the taskbar, so the app's windows show
 * on top of it. It never takes focus. It closes when an app window is made fullscreen there, the start fails, the
 * stream ends, or at the latest after {@link WindowPlacement#FIRST_WINDOW_WITHIN}, when a window is no longer taken for
 * the app's anyway.
 *
 * <p>Drawn on the agent's idle screen's blue, since it shows on the same TV; it's not part of the themed UI.
 */
final class LaunchSplash {

    private static final Logger LOG = Logger.getLogger(LaunchSplash.class.getName());
    static final String TITLE = "Orbit: starting a stream";
    static final Color BACKGROUND_TOP = new Color(0x0E1726);
    static final Color BACKGROUND_BOTTOM = new Color(0x1C2E45);
    /** One turn of the spinner. */
    private static final double TURN_MILLIS = 1200;
    private static final int FRAME_MILLIS = 33;

    /** Counts {@link #show} calls; a close covers every show before it, even one whose window hasn't opened yet. */
    private final AtomicLong shows = new AtomicLong();
    private final AtomicLong closedUpTo = new AtomicLong();
    // Event thread only.
    private JFrame frame;
    private Timer spin;
    private Timer limit;

    /** Shows that {@code app} is starting, with its cover if it has one; replaces a splash still showing. */
    void show(String app, Optional<Path> coverFile) {
        long show = shows.incrementAndGet();
        BufferedImage cover = coverFile.map(LaunchSplash::read).orElse(null);
        SwingUtilities.invokeLater(() -> {
            if (closedUpTo.get() >= show) {
                return;
            }
            dispose();
            open(app, cover);
        });
    }

    /** Closes the splash, from any thread; nothing if none shows. */
    void close() {
        closedUpTo.accumulateAndGet(shows.get(), Math::max);
        SwingUtilities.invokeLater(this::dispose);
    }

    private static BufferedImage read(Path file) {
        try {
            return ImageIO.read(file.toFile());
        } catch (IOException e) {
            LOG.log(Level.FINE, "cannot read the cover " + file, e);
            return null;
        }
    }

    private void open(String app, BufferedImage cover) {
        Canvas canvas = new Canvas(app, cover);
        frame = new JFrame(TITLE);
        frame.setUndecorated(true);
        // The window manager then never gives it focus, which stays with whatever the stream's keys are for.
        frame.setFocusableWindowState(false);
        frame.setAutoRequestFocus(false);
        frame.setBackground(BACKGROUND_TOP);
        frame.setContentPane(canvas);
        // The desktop's window placement makes it fullscreen on Sunshine's monitor as soon as it's mapped.
        frame.setSize(1280, 720);
        frame.setVisible(true);
        long started = System.nanoTime();
        spin = new Timer(FRAME_MILLIS, e -> canvas.spin((System.nanoTime() - started) / 1e6 / TURN_MILLIS));
        spin.start();
        limit = new Timer((int) WindowPlacement.FIRST_WINDOW_WITHIN.toMillis(), e -> dispose());
        limit.setRepeats(false);
        limit.start();
    }

    private void dispose() {
        if (spin != null) {
            spin.stop();
            limit.stop();
            spin = null;
            limit = null;
        }
        if (frame != null) {
            frame.dispose();
            frame = null;
        }
    }

    /** The splash at {@code width}×{@code height}, the spinner at {@code turns} (for checking it off-screen). */
    static BufferedImage render(int width, int height, String app, BufferedImage cover, double turns) {
        BufferedImage img = base(width, height, app, cover);
        Graphics2D g = img.createGraphics();
        paintSpinner(g, spinnerArea(width, height), turns);
        g.dispose();
        return img;
    }

    /** Everything but the spinner: background, cover (or the Orbit logo) and the caption. */
    static BufferedImage base(int width, int height, String app, BufferedImage cover) {
        BufferedImage img = Dither.verticalGradient(width, height, BACKGROUND_TOP, BACKGROUND_BOTTOM);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        Rectangle art = artArea(width, height);
        if (cover != null) {
            // Covers are 3:4 box art, but any shape fits: scaled into the area, centred.
            double scale = Math.min((double) art.width / cover.getWidth(), (double) art.height / cover.getHeight());
            int w = (int) Math.round(cover.getWidth() * scale);
            int h = (int) Math.round(cover.getHeight() * scale);
            int x = art.x + (art.width - w) / 2;
            int y = art.y + (art.height - h) / 2;
            double radius = h * 0.04;
            for (int i = 1; i <= 6; i++) {
                // A soft shadow below, so the cover stands out from the background.
                g.setColor(new Color(0, 0, 0, 22));
                g.fill(new RoundRectangle2D.Double(x - i, y - i + h * 0.012, w + 2 * i, h + 2 * i,
                        radius + i * 2, radius + i * 2));
            }
            Graphics2D clip = (Graphics2D) g.create();
            clip.clip(new RoundRectangle2D.Double(x, y, w, h, radius * 2, radius * 2));
            clip.drawImage(cover, x, y, w, h, null);
            clip.dispose();
        } else {
            int size = Math.min(art.width, art.height) * 3 / 4;
            Logo.paint(g, art.x + (art.width - size) / 2.0, art.y + (art.height - size) / 2.0, size);
        }

        Font font = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(16, height / 18));
        g.setFont(font);
        FontMetrics m = g.getFontMetrics();
        String caption = "Starting " + app;
        int baseline = art.y + art.height + height / 14 + m.getAscent();
        g.setColor(Color.WHITE);
        g.drawString(caption, (width - m.stringWidth(caption)) / 2, baseline);

        // Orbit's mark, small and dimmed in the corner, as on the idle screen.
        int mark = Math.max(16, height / 20);
        int margin = Math.max(16, height / 22);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
        Logo.paint(g, width - margin - mark, height - margin - mark, mark);
        g.dispose();
        return img;
    }

    /** Where the cover goes: the upper middle, about half the height. */
    private static Rectangle artArea(int width, int height) {
        int h = height * 46 / 100;
        int w = Math.min(width * 6 / 10, h * 3 / 2);
        return new Rectangle((width - w) / 2, height * 13 / 100, w, h);
    }

    /** The spinner's square, below the caption; also what's repainted for each frame. */
    static Rectangle spinnerArea(int width, int height) {
        int size = Math.max(16, height / 22);
        int top = height * 13 / 100 + height * 46 / 100 + height / 14 + height / 18 + height / 16;
        return new Rectangle((width - size) / 2, top, size, size);
    }

    private static void paintSpinner(Graphics2D g, Rectangle area, double turns) {
        Graphics2D s = (Graphics2D) g.create();
        s.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float stroke = Math.max(2f, area.width / 7f);
        double inset = stroke / 2.0;
        double start = -turns * 360;
        s.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        s.setColor(new Color(255, 255, 255, 50));
        s.draw(new Arc2D.Double(area.x + inset, area.y + inset, area.width - stroke, area.height - stroke, 0, 360,
                Arc2D.OPEN));
        s.setColor(Logo.MOON);
        s.draw(new Arc2D.Double(area.x + inset, area.y + inset, area.width - stroke, area.height - stroke, start, 100,
                Arc2D.OPEN));
        s.dispose();
    }

    /** Draws the base image for its current size, and the spinner over it. */
    private static final class Canvas extends JComponent {
        private final String app;
        private final BufferedImage cover;
        private BufferedImage base;
        private double turns;

        Canvas(String app, BufferedImage cover) {
            this.app = app;
            this.cover = cover;
            setOpaque(true);
        }

        void spin(double turns) {
            this.turns = turns;
            Rectangle area = spinnerArea(getWidth(), getHeight());
            repaint(area.x - 2, area.y - 2, area.width + 4, area.height + 4);
        }

        @Override
        protected void paintComponent(Graphics g) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            if (base == null || base.getWidth() != w || base.getHeight() != h) {
                base = base(w, h, app, cover);
            }
            Graphics2D g2 = (Graphics2D) g;
            g2.drawImage(base, 0, 0, null);
            paintSpinner(g2, spinnerArea(w, h), turns);
        }
    }
}
