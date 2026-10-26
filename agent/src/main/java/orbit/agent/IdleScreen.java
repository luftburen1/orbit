package orbit.agent;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Graphics;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import orbit.protocol.Dither;
import orbit.protocol.RemoteIcon;

/**
 * Fullscreen picture shown while the client isn't streaming, with the client's name and status in a corner. On a
 * desktop, Escape dismisses it in case someone needs the desktop: until the tray icon shows it again, or without a
 * tray, until after the next stream.
 */
public final class IdleScreen {

    private static final Logger LOG = Logger.getLogger(IdleScreen.class.getName());

    /** What the agent calls; methods may be called from any thread. */
    public interface Display {
        void show(String status);

        /** Shows the screen with the list of apps to pick from in the middle, or without one when it's null. */
        default void show(String status, AppMenu menu) {
            show(status);
        }

        /** Shows or removes the TV remote in the top-right corner: the agent reads the remote over HDMI-CEC. */
        default void setRemote(boolean remote) {
        }

        /** A key from the TV remote came in: the remote in the corner lights up green for {@link #FLASH}. */
        default void flashRemote() {
        }

        /** Replaces the software line in the corner, e.g. once Moonlight has been installed. */
        default void setVersions(String versions) {
        }

        /** Hides the screen because a stream is starting or running. */
        void hide();

        /** Brings the screen back after Escape or when it started hidden, e.g. from the tray; show() then opens it. */
        default void reveal() {
        }
    }

    /** For clients without a display (or tests): does nothing. */
    static final Display NONE = new Display() {
        @Override
        public void show(String status) {
        }

        @Override
        public void hide() {
        }
    };

    /**
     * The software line in the corner, e.g. "orbit-agent 0.1.0 · Moonlight 6.1.0".
     *
     * @param moonlightVersion as {@code moonlight --version} prints it, or null if unknown
     */
    static String versionLine(String moonlightVersion) {
        String line = "orbit-agent " + AgentInfo.VERSION;
        if (moonlightVersion == null || moonlightVersion.isBlank()) {
            return line;
        }
        String moonlight = moonlightVersion.strip();
        return line + " · "
                + (moonlight.regionMatches(true, 0, "Moonlight", 0, 9) ? moonlight : "Moonlight " + moonlight);
    }

    /**
     * The idle screen on a text console, where Moonlight draws straight to the display: the framebuffer, or
     * {@link #NONE} if it isn't usable.
     *
     * @param versions the software line for the corner, see {@link #versionLine}
     * @param animate  animate the logo (only shown when there's no picture of the user's own)
     */
    static Display console(Path image, String hostname, String versions, boolean animate) {
        return FramebufferScreen.open(image, hostname, versions, animate).orElse(NONE);
    }

    /**
     * The fullscreen window on a desktop (X11 or Wayland).
     *
     * @param showAtStart    open it as soon as the agent starts; else only once {@link Display#reveal revealed}
     * @param staysDismissed after Escape (or starting hidden) it stays closed until revealed again, since the tray
     *                       can bring it back; without a tray it comes back after the next stream instead
     */
    static Display window(Path image, String hostname, String versions, boolean animate, boolean showAtStart,
            boolean staysDismissed) {
        return new Window(image, hostname, versions, animate, showAtStart, staysDismissed);
    }

    /** The idle screen with the logo standing still. */
    static void paint(Graphics2D g, int width, int height, BufferedImage image, String hostname, String status,
            String versions) {
        paint(g, width, height, image, hostname, status, versions, 0, false);
    }

    /** The idle screen, with the logo's effects at {@code seconds} into the animation if {@code animate}. */
    static void paint(Graphics2D g, int width, int height, BufferedImage image, String hostname, String status,
            String versions, double seconds, boolean animate) {
        paint(g, width, height, image, hostname, status, versions, seconds, animate, null);
    }

    /** The idle screen, with {@code menu} in the logo's place if it isn't null. */
    static void paint(Graphics2D g, int width, int height, BufferedImage image, String hostname, String status,
            String versions, double seconds, boolean animate, AppMenu menu) {
        paintBase(g, width, height, image, hostname, status, versions);
        if (menu != null) {
            paintMenu(g, width, height, menu);
        } else if (image == null) {
            Rectangle region = LogoEffects.region(width, height);
            BufferedImage area = copy(background(width, height), region);
            LogoEffects.paint(area, region, width, height, seconds, animate);
            g.drawImage(area, region.x, region.y, null);
        }
    }

    /** {@code region} of {@code source}, as a new {@code TYPE_INT_RGB} image to draw the logo's effects on. */
    static BufferedImage copy(BufferedImage source, Rectangle region) {
        BufferedImage area = new BufferedImage(Math.max(1, region.width), Math.max(1, region.height),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = area.createGraphics();
        g.drawImage(source, -region.x, -region.y, null);
        g.dispose();
        return area;
    }

    /**
     * Everything but the logo: the picture or background, the caption and the software line. The logo goes into
     * {@link LogoEffects#region}, which none of these overlap.
     */
    static void paintBase(Graphics2D g, int width, int height, BufferedImage image, String hostname, String status,
            String versions) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        if (image != null) {
            double scale = Math.max((double) width / image.getWidth(), (double) height / image.getHeight());
            int w = (int) Math.ceil(image.getWidth() * scale);
            int h = (int) Math.ceil(image.getHeight() * scale);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, width, height);
            g.drawImage(image, (width - w) / 2, (height - h) / 2, w, h, null);
        } else {
            // Without a picture of the user's own, the Orbit logo goes on top; see paint.
            g.drawImage(background(width, height), 0, 0, null);
        }

        try {
            paintCaption(g, width, height, hostname, status);
            paintVersions(g, width, height, versions);
        } catch (RuntimeException | InternalError | LinkageError e) {
            // Minimal systems may have no fonts (install e.g. fonts-dejavu-core); show the picture without text.
            if (!captionFailed) {
                captionFailed = true;
                LOG.log(Level.WARNING, "cannot draw the idle screen's text; is a font package installed?", e);
            }
        }
    }

    private static volatile boolean captionFailed;

    private static final Color BACKGROUND_TOP = new Color(0x0E1726);
    private static final Color BACKGROUND_BOTTOM = new Color(0x1C2E45);
    /** The last background drawn; the gradient is dark and spans few shades, so it is dithered, once per size. */
    private static BufferedImage background;

    private static synchronized BufferedImage background(int width, int height) {
        if (background == null || background.getWidth() != width || background.getHeight() != height) {
            background = Dither.verticalGradient(width, height, BACKGROUND_TOP, BACKGROUND_BOTTOM);
        }
        return background;
    }

    /** The software line, small and dimmed in the bottom-right corner. */
    private static void paintVersions(Graphics2D g, int width, int height, String text) {
        int margin = Math.max(16, height / 22);
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(10, height / 60));
        g.setFont(font);
        int textWidth = g.getFontMetrics().stringWidth(text);
        int textHeight = g.getFontMetrics().getHeight();
        int pad = textHeight / 3;
        int boxWidth = textWidth + 2 * pad;
        int boxHeight = textHeight + pad;
        int boxX = width - margin - boxWidth;
        int boxY = height - margin - boxHeight;
        g.setColor(new Color(0, 0, 0, 110));
        g.fill(new RoundRectangle2D.Double(boxX, boxY, boxWidth, boxHeight, pad * 2, pad * 2));
        g.setColor(new Color(255, 255, 255, 160));
        g.drawString(text, boxX + pad, boxY + pad / 2 + g.getFontMetrics().getAscent());
    }

    /**
     * The TV remote, small and dimmed in the top-right corner like the software line, when the agent reads the remote
     * over HDMI-CEC: the client can be used with it.
     */
    static void paintRemote(Graphics2D g, int width, int height) {
        paintRemote(g, width, height, false);
    }

    /** @param lit green, as for a moment after each of the remote's keys: the agent got it */
    static void paintRemote(Graphics2D g, int width, int height, boolean lit) {
        int size = Math.max(16, height / 16);
        int pad = size / 5;
        Rectangle box = remoteRegion(width, height);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0, 0, 0, 110));
        g.fill(new RoundRectangle2D.Double(box.x, box.y, box.width, box.height, pad * 2, pad * 2));
        g.setColor(lit ? REMOTE_LIT : new Color(255, 255, 255, 190));
        Graphics2D g2 = (Graphics2D) g.create();
        g2.translate(box.x + pad - size * 0.27, box.y + pad - size * 0.03);
        g2.fill(RemoteIcon.shape(size));
        g2.dispose();
    }

    /** How long the remote stays lit after a key. */
    static final Duration FLASH = Duration.ofMillis(120);
    private static final Color REMOTE_LIT = new Color(0xD4F58C);

    /** The remote's box in the top-right corner, which is all a flash redraws. */
    static Rectangle remoteRegion(int width, int height) {
        int margin = Math.max(16, height / 22);
        int size = Math.max(16, height / 16);
        int pad = size / 5;
        // The remote stands in the middle 46 % of its square and fills 94 % of its height; the box fits it.
        int boxWidth = (int) Math.round(size * 0.46) + 2 * pad;
        int boxHeight = (int) Math.round(size * 0.94) + 2 * pad;
        return new Rectangle(width - margin - boxWidth, margin, boxWidth, boxHeight);
    }

    /** What the desktop window shows at the top for its first {@link #HINT_SECONDS} seconds. */
    static final String ESCAPE_HINT = "Press Esc to close the idle screen";
    static final int HINT_SECONDS = 5;

    /** A short hint at the top centre, in the same box as the software line. */
    static void paintHint(Graphics2D g, int width, int height, String text) {
        int margin = Math.max(16, height / 22);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(11, height / 50)));
        int textWidth = g.getFontMetrics().stringWidth(text);
        int textHeight = g.getFontMetrics().getHeight();
        int pad = textHeight / 3;
        int boxWidth = textWidth + 3 * pad;
        int boxHeight = textHeight + pad;
        int boxX = (width - boxWidth) / 2;
        g.setColor(new Color(0, 0, 0, 110));
        g.fill(new RoundRectangle2D.Double(boxX, margin, boxWidth, boxHeight, pad * 2, pad * 2));
        g.setColor(new Color(255, 255, 255, 190));
        g.drawString(text, boxX + 3 * pad / 2, margin + pad / 2 + g.getFontMetrics().getAscent());
    }

    static final String MENU_TITLE = "Choose an app";
    static final String MENU_HINT = "▲ ▼ choose  ·  OK starts  ·  Stop closes";
    private static final Color MENU_SELECTED = new Color(0x3D7BD9);

    /** The list of apps in a panel in the middle, the selected one highlighted, scrolled to keep it in view. */
    static void paintMenu(Graphics2D g, int width, int height, AppMenu menu) {
        try {
            Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(14, height / 28));
            Font itemFont = new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(12, height / 34));
            Font hintFont = new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(10, height / 56));
            int itemHeight = g.getFontMetrics(itemFont).getHeight();
            int pad = itemHeight;
            // With covers, rows are tall enough for a small one (box art, 3:4) before the name.
            int iconHeight = menu.hasIcons() ? itemHeight * 2 : 0;
            int iconWidth = iconHeight * 3 / 4;
            int rowHeight = menu.hasIcons() ? iconHeight + pad / 2 : itemHeight * 8 / 5;
            int textX = menu.hasIcons() ? iconWidth + pad / 2 : 0;
            int titleHeight = g.getFontMetrics(titleFont).getHeight();
            int hintHeight = g.getFontMetrics(hintFont).getHeight();
            int rows = Math.max(1, Math.min(menu.apps().size(),
                    (height * 3 / 5 - titleHeight - hintHeight - 3 * pad) / rowHeight));
            int widest = g.getFontMetrics(titleFont).stringWidth(MENU_TITLE);
            widest = Math.max(widest, g.getFontMetrics(hintFont).stringWidth(MENU_HINT));
            for (String app : menu.apps()) {
                widest = Math.max(widest, textX + g.getFontMetrics(itemFont).stringWidth(app) + pad);
            }
            int boxWidth = Math.min(width - 2 * pad, Math.max(width / 3, widest + 2 * pad));
            int boxHeight = titleHeight + rows * rowHeight + hintHeight + 3 * pad;
            int boxX = (width - boxWidth) / 2;
            int boxY = (height - boxHeight) / 2;

            g.setColor(new Color(0, 0, 0, 170));
            g.fill(new RoundRectangle2D.Double(boxX, boxY, boxWidth, boxHeight, pad, pad));
            g.setColor(Color.WHITE);
            g.setFont(titleFont);
            g.drawString(MENU_TITLE, boxX + pad, boxY + pad + g.getFontMetrics().getAscent());

            int first = menu.firstVisible(rows);
            int y = boxY + pad + titleHeight + pad / 2;
            g.setFont(itemFont);
            for (int i = first; i < first + rows; i++) {
                if (i == menu.selected()) {
                    g.setColor(MENU_SELECTED);
                    g.fill(new RoundRectangle2D.Double(boxX + pad / 2.0, y, boxWidth - pad, rowHeight,
                            pad / 2.0, pad / 2.0));
                }
                if (menu.hasIcons()) {
                    paintIcon(g, menu.icon(i), boxX + pad, y + (rowHeight - iconHeight) / 2, iconWidth, iconHeight,
                            pad / 4);
                }
                g.setColor(i == menu.selected() ? Color.WHITE : new Color(255, 255, 255, 200));
                int baseline = y + (rowHeight - itemHeight) / 2 + g.getFontMetrics().getAscent();
                g.drawString(menu.apps().get(i), boxX + pad + textX, baseline);
                // More apps above or below than fit.
                String more = i == first && first > 0 ? "▲"
                        : i == first + rows - 1 && i < menu.apps().size() - 1 ? "▼" : null;
                if (more != null) {
                    g.drawString(more, boxX + boxWidth - pad - g.getFontMetrics().stringWidth(more), baseline);
                }
                y += rowHeight;
            }
            g.setFont(hintFont);
            g.setColor(new Color(255, 255, 255, 160));
            g.drawString(MENU_HINT, boxX + pad, y + pad / 2 + g.getFontMetrics().getAscent());
        } catch (RuntimeException | InternalError | LinkageError e) {
            // No fonts: the caption already logged it.
        }
    }

    /**
     * An app's cover in the {@code width}×{@code height} box at ({@code x}, {@code y}) with rounded corners, filling
     * it (cut to fit); a dim empty box for an app without one, so the names stay aligned.
     */
    private static void paintIcon(Graphics2D g, BufferedImage icon, int x, int y, int width, int height, int arc) {
        RoundRectangle2D.Double box = new RoundRectangle2D.Double(x, y, width, height, arc * 2, arc * 2);
        if (icon == null) {
            g.setColor(new Color(255, 255, 255, 30));
            g.fill(box);
            return;
        }
        Graphics2D clip = (Graphics2D) g.create();
        clip.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        clip.clip(box);
        double scale = Math.max((double) width / icon.getWidth(), (double) height / icon.getHeight());
        int w = (int) Math.ceil(icon.getWidth() * scale);
        int h = (int) Math.ceil(icon.getHeight() * scale);
        clip.drawImage(icon, x + (width - w) / 2, y + (height - h) / 2, w, h, null);
        clip.dispose();
    }

    /** The name with its first letter in capitals, e.g. "livingroom" as "Livingroom"; the rest is unchanged. */
    static String capitalized(String name) {
        if (name.isEmpty()) {
            return name;
        }
        int first = name.offsetByCodePoints(0, 1);
        return name.substring(0, first).toUpperCase(Locale.ROOT) + name.substring(first);
    }

    private static void paintCaption(Graphics2D g, int width, int height, String hostname, String status) {
        String name = capitalized(hostname);
        int margin = Math.max(16, height / 22);
        Font nameFont = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(14, height / 24));
        Font statusFont = new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(11, height / 40));
        int nameWidth = g.getFontMetrics(nameFont).stringWidth(name);
        int statusWidth = g.getFontMetrics(statusFont).stringWidth(status);
        int nameHeight = g.getFontMetrics(nameFont).getHeight();
        int statusHeight = g.getFontMetrics(statusFont).getHeight();
        int pad = statusHeight / 2;
        int boxWidth = Math.max(nameWidth, statusWidth) + 2 * pad;
        int boxHeight = nameHeight + statusHeight + 2 * pad;
        int boxX = margin;
        int boxY = height - margin - boxHeight;

        // A translucent panel keeps the text readable on any picture.
        g.setColor(new Color(0, 0, 0, 140));
        g.fill(new RoundRectangle2D.Double(boxX, boxY, boxWidth, boxHeight, pad * 2, pad * 2));
        g.setColor(Color.WHITE);
        g.setFont(nameFont);
        g.drawString(name, boxX + pad, boxY + pad + g.getFontMetrics().getAscent());
        g.setColor(new Color(255, 255, 255, 210));
        g.setFont(statusFont);
        g.drawString(status, boxX + pad, boxY + pad + nameHeight + g.getFontMetrics().getAscent());
    }

    /** The real window. All Swing work happens on the event thread. */
    private static final class Window implements Display {
        private final IdleImage image;
        private final String hostname;
        private volatile String versions;
        private final boolean animate;
        private final long started = System.nanoTime();
        /** Repaints the logo's region while the window is open and animating. */
        private Timer animation;
        /** Removes the Escape hint {@link #HINT_SECONDS} after the window opened. */
        private Timer hintTimer;
        private boolean hintShown;
        private JFrame frame;
        private String status = "";
        private AppMenu menu;
        private boolean remote;
        /** The remote shows green after a key, until {@link #unlight} runs. */
        private boolean remoteLit;
        private Timer unlight;
        /** Escape pressed (or started hidden); see {@link #staysDismissed} for how long it stays hidden. */
        private boolean dismissed;
        /** True: hidden until {@link #reveal}; false: until the next stream has come and gone. */
        private final boolean staysDismissed;

        Window(Path imagePath, String hostname, String versions, boolean animate, boolean showAtStart,
                boolean staysDismissed) {
            this.animate = animate;
            this.image = new IdleImage(imagePath);
            this.hostname = hostname;
            this.versions = versions;
            this.dismissed = !showAtStart;
            this.staysDismissed = staysDismissed;
        }

        @Override
        public void reveal() {
            SwingUtilities.invokeLater(() -> dismissed = false);
        }

        @Override
        public void setVersions(String versions) {
            this.versions = versions;
            SwingUtilities.invokeLater(() -> {
                if (frame != null) {
                    frame.repaint();
                }
            });
        }

        @Override
        public void setRemote(boolean newRemote) {
            SwingUtilities.invokeLater(() -> {
                if (remote != newRemote) {
                    remote = newRemote;
                    if (frame != null) {
                        frame.getContentPane().repaint();
                    }
                }
            });
        }

        @Override
        public void flashRemote() {
            SwingUtilities.invokeLater(() -> {
                if (!remote || frame == null) {
                    return;
                }
                remoteLit = true;
                repaintRemote();
                if (unlight == null) {
                    unlight = new Timer((int) FLASH.toMillis(), e -> {
                        remoteLit = false;
                        repaintRemote();
                    });
                    unlight.setRepeats(false);
                }
                // Another key while lit keeps it lit.
                unlight.restart();
            });
        }

        private void repaintRemote() {
            if (frame != null) {
                JComponent content = (JComponent) frame.getContentPane();
                Rectangle r = remoteRegion(content.getWidth(), content.getHeight());
                content.repaint(r.x - 1, r.y - 1, r.width + 2, r.height + 2);
            }
        }

        @Override
        public void show(String newStatus) {
            show(newStatus, null);
        }

        @Override
        public void show(String newStatus, AppMenu newMenu) {
            SwingUtilities.invokeLater(() -> {
                status = newStatus;
                menu = newMenu;
                if (dismissed) {
                    return;
                }
                boolean imageChanged = image.reloadIfChanged();
                if (frame == null) {
                    open();
                } else if (imageChanged) {
                    frame.repaint();
                } else {
                    frame.getContentPane().repaint();
                }
            });
        }

        @Override
        public void hide() {
            SwingUtilities.invokeLater(() -> {
                if (!staysDismissed) {
                    dismissed = false;
                }
                close();
            });
        }

        private void open() {
            frame = new JFrame("Orbit");
            frame.setUndecorated(true);
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.setContentPane(new JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    IdleScreen.paint((Graphics2D) g, getWidth(), getHeight(), image.image(), hostname, status, versions,
                            (System.nanoTime() - started) / 1e9, animate, menu);
                    if (remote) {
                        paintRemote((Graphics2D) g, getWidth(), getHeight(), remoteLit);
                    }
                    if (hintShown) {
                        try {
                            paintHint((Graphics2D) g, getWidth(), getHeight(), ESCAPE_HINT);
                        } catch (RuntimeException | InternalError | LinkageError e) {
                            // No fonts: the caption already logged it.
                        }
                    }
                }
            });
            frame.setCursor(Toolkit.getDefaultToolkit().createCustomCursor(
                    new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), new Point(), "none"));
            frame.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                        dismissed = true;
                        close();
                    }
                }
            });
            GraphicsDevice screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            if (screen.isFullScreenSupported()) {
                screen.setFullScreenWindow(frame);
            } else {
                frame.setBounds(screen.getDefaultConfiguration().getBounds());
                frame.setVisible(true);
            }
            frame.toFront();
            frame.requestFocus();
            hintShown = true;
            hintTimer = new Timer(HINT_SECONDS * 1000, e -> {
                hintShown = false;
                if (frame != null) {
                    frame.getContentPane().repaint();
                }
            });
            hintTimer.setRepeats(false);
            hintTimer.start();
            if (animate) {
                animation = new Timer(1000 / LogoEffects.FPS, e -> {
                    if (frame != null && image.image() == null && menu == null) {
                        JComponent content = (JComponent) frame.getContentPane();
                        content.repaint(LogoEffects.region(content.getWidth(), content.getHeight()));
                    }
                });
                animation.start();
            }
        }

        private void close() {
            if (animation != null) {
                animation.stop();
                animation = null;
            }
            if (hintTimer != null) {
                hintTimer.stop();
                hintTimer = null;
            }
            if (frame == null) {
                return;
            }
            GraphicsDevice screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            if (screen.getFullScreenWindow() == frame) {
                screen.setFullScreenWindow(null);
            }
            frame.dispose();
            frame = null;
        }
    }

    private IdleScreen() {
    }
}
