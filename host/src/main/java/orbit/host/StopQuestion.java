package orbit.host;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import orbit.protocol.Dither;
import orbit.protocol.Logo;
import orbit.protocol.Message;

/**
 * "Stop the stream?" on Sunshine's monitor, so it appears in the stream: asked when a client's TV remote stops a
 * stream whose app would quit. The client's remote answers it; this only shows the question and which button is
 * selected. It's an override-redirect popup, so no window manager places it, it never takes focus, and it shows above
 * a fullscreen game, which a window kept above others wouldn't. It closes when the client says so, or after
 * {@link #LIMIT}.
 *
 * <p>Drawn on the agent's idle screen's blue, like {@link LaunchSplash}; it's not part of the themed UI.
 */
final class StopQuestion {

    /** Longer than the client keeps it open, so a client that went away doesn't leave it behind. */
    static final int LIMIT = 45_000;
    private static final String TITLE = "Stop the stream?";
    private static final String HINT = "◀ ▶ choose    OK confirms    Back cancels";

    // Event thread only.
    private JWindow window;
    private Canvas canvas;
    private Timer limit;

    /**
     * Shows the question, with Cancel selected, centred on the monitor at {@code desktop} (its place in the desktop's
     * logical coordinates); replaces one still showing. From any thread.
     *
     * @param quitsApp the app a stop quits, or empty if unknown
     */
    void show(Rectangle desktop, String quitsApp) {
        SwingUtilities.invokeLater(() -> {
            dispose();
            Rectangle screen = deviceBounds(desktop, Arrays.stream(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
                    .map(d -> d.getDefaultConfiguration().getBounds()).toList());
            int width = screen.width * 46 / 100;
            int height = width * 2 / 5;
            canvas = new Canvas(Message.StopCheck.warning(quitsApp));
            window = new JWindow();
            // Override-redirect: above everything, a fullscreen game included, and never focused.
            window.setType(Window.Type.POPUP);
            window.setFocusableWindowState(false);
            window.setAutoRequestFocus(false);
            window.setAlwaysOnTop(true);
            window.setContentPane(canvas);
            window.setBounds(screen.x + (screen.width - width) / 2, screen.y + (screen.height - height) / 2, width,
                    height);
            window.setVisible(true);
            limit = new Timer(LIMIT, e -> dispose());
            limit.setRepeats(false);
            limit.start();
        });
    }

    /** Moves the selection: OK, which stops, or Cancel. From any thread; nothing if none shows. */
    void select(boolean ok) {
        SwingUtilities.invokeLater(() -> {
            if (canvas != null) {
                canvas.select(ok);
            }
        });
    }

    /** Closes the question, from any thread; nothing if none shows. */
    void close() {
        SwingUtilities.invokeLater(this::dispose);
    }

    private void dispose() {
        if (limit != null) {
            limit.stop();
            limit = null;
        }
        if (window != null) {
            window.dispose();
            window = null;
            canvas = null;
        }
    }

    /**
     * Where AWT has the monitor whose desktop bounds are {@code desktop}: the screen device starting at the same
     * place, else the one starting nearest, since XWayland may scale the coordinates; {@code desktop} itself without
     * devices.
     */
    static Rectangle deviceBounds(Rectangle desktop, List<Rectangle> devices) {
        return devices.stream()
                .min(Comparator.comparingDouble(d -> d.getLocation().distance(desktop.getLocation())))
                .orElse(desktop);
    }

    /** The question at {@code width}×{@code height} (for checking it off-screen). */
    static BufferedImage render(int width, int height, String warning, boolean okSelected) {
        BufferedImage img = Dither.verticalGradient(width, height, LaunchSplash.BACKGROUND_TOP,
                LaunchSplash.BACKGROUND_BOTTOM);
        Graphics2D g = img.createGraphics();
        paint(g, width, height, warning, okSelected);
        g.dispose();
        return img;
    }

    private static void paint(Graphics2D g, int width, int height, String warning, boolean okSelected) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        float line = Math.max(2f, height / 120f);
        g.setStroke(new BasicStroke(line));
        g.setColor(new Color(Logo.MOON.getRed(), Logo.MOON.getGreen(), Logo.MOON.getBlue(), 160));
        g.drawRect(Math.round(line / 2), Math.round(line / 2), width - Math.round(line), height - Math.round(line));

        int margin = width / 16;
        int y = height / 7;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(14, height / 9)));
        FontMetrics m = g.getFontMetrics();
        y += m.getAscent();
        g.setColor(Color.WHITE);
        g.drawString(TITLE, margin, y);

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(11, height / 16)));
        m = g.getFontMetrics();
        y += m.getHeight() / 2;
        g.setColor(new Color(255, 255, 255, 215));
        // A line for each sentence: what quits, and what that loses.
        for (String sentence : warning.split("(?<=\\.) ")) {
            for (String l : wrap(sentence, m, width - 2 * margin)) {
                y += m.getHeight();
                g.drawString(l, margin, y);
            }
        }

        Font buttonFont = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(11, height / 15));
        g.setFont(buttonFont);
        m = g.getFontMetrics();
        int buttonHeight = m.getHeight() * 7 / 4;
        int buttonWidth = Math.max(m.stringWidth("Cancel") * 2, width / 5);
        int gap = width / 30;
        int hintSize = Math.max(10, height / 22);
        int buttonY = height - height / 9 - hintSize * 2 - buttonHeight;
        int okX = width - margin - 2 * buttonWidth - gap;
        button(g, okX, buttonY, buttonWidth, buttonHeight, "OK", okSelected);
        button(g, okX + buttonWidth + gap, buttonY, buttonWidth, buttonHeight, "Cancel", !okSelected);

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, hintSize));
        m = g.getFontMetrics();
        g.setColor(new Color(255, 255, 255, 140));
        g.drawString(HINT, width - margin - m.stringWidth(HINT), buttonY + buttonHeight + hintSize * 2);
    }

    private static void button(Graphics2D g, int x, int y, int w, int h, String label, boolean selected) {
        RoundRectangle2D shape = new RoundRectangle2D.Double(x, y, w, h, h / 2.5, h / 2.5);
        if (selected) {
            g.setColor(Logo.MOON);
            g.fill(shape);
            g.setColor(LaunchSplash.BACKGROUND_TOP);
        } else {
            g.setColor(new Color(255, 255, 255, 120));
            g.setStroke(new BasicStroke(Math.max(1.5f, h / 24f)));
            g.draw(shape);
            g.setColor(Color.WHITE);
        }
        FontMetrics m = g.getFontMetrics();
        g.drawString(label, x + (w - m.stringWidth(label)) / 2, y + (h - m.getHeight()) / 2 + m.getAscent());
    }

    /** {@code text} in lines no wider than {@code width}, broken between words. */
    static List<String> wrap(String text, FontMetrics m, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String tried = current.isEmpty() ? word : current + " " + word;
            if (!current.isEmpty() && m.stringWidth(tried) > width) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(tried);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static final class Canvas extends JComponent {
        private final String warning;
        private boolean okSelected;

        Canvas(String warning) {
            this.warning = warning;
            setOpaque(true);
        }

        void select(boolean ok) {
            okSelected = ok;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (getWidth() > 0 && getHeight() > 0) {
                g.drawImage(render(getWidth(), getHeight(), warning, okSelected), 0, 0, null);
            }
        }
    }
}
