package orbit.agent;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import orbit.protocol.Dither;

/**
 * Idle screen for clients without X11 or Wayland: draws straight into the Linux framebuffer ({@code /dev/fb0}),
 * the same way the text console does. Moonlight takes over the display while streaming, so nothing is drawn then.
 *
 * <p>The console can paint over the framebuffer (its cursor, log output, its redraw after Moonlight exits), so each
 * {@link #show} call writes the whole frame again; the agent calls it every few seconds while idle.
 */
final class FramebufferScreen implements IdleScreen.Display {

    private static final Logger LOG = Logger.getLogger(FramebufferScreen.class.getName());
    private static final Pattern MODE = Pattern.compile("(\\d+)x(\\d+)");

    /** Visible size and memory layout of a framebuffer. */
    record Geometry(int width, int height, int bitsPerPixel, int stride) {
    }

    private final Path device;
    private final Geometry geometry;
    private final IdleImage image;
    private final String hostname;
    /** Painter thread only. */
    private String versions;
    private final Optional<Path> console;
    /** Painter thread only; false after a stream, when Moonlight may have shown the cursor again. */
    private boolean cursorHidden;
    /** Painter thread only; what was last logged about graphics mode, so it's logged once, not after every stream. */
    private Boolean graphicsModeLogged;
    /** Draws and writes everything, so frames never interleave; also runs the animation. */
    private final ScheduledExecutorService painter = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("orbit-framebuffer").factory());

    private final boolean animate;
    private final long started = System.nanoTime();

    // Painter thread only.
    private String renderedStatus;
    private AppMenu renderedMenu;
    private boolean remote;
    /** The remote shows green after a key, until {@link #unlight} runs. */
    private boolean remoteLit;
    private ScheduledFuture<?> unlight;
    /** Everything but the logo, for redrawing the logo's region on each animation frame. */
    private BufferedImage base;
    /** The whole screen as written to the framebuffer; animation frames update the logo's rows in it. */
    private ByteBuffer frame;
    private boolean showing;
    private ScheduledFuture<?> animation;
    private boolean animationFailed;

    FramebufferScreen(Path device, Geometry geometry, Path imagePath, String hostname, String versions) {
        this(device, geometry, imagePath, hostname, versions, Optional.empty());
    }

    FramebufferScreen(Path device, Geometry geometry, Path imagePath, String hostname, String versions,
            Optional<Path> console) {
        this(device, geometry, imagePath, hostname, versions, console, false);
    }

    /** @param animate animate the logo while the idle screen shows (only when there's no picture of the user's) */
    FramebufferScreen(Path device, Geometry geometry, Path imagePath, String hostname, String versions,
            Optional<Path> console, boolean animate) {
        this.animate = animate;
        this.device = device;
        this.geometry = geometry;
        this.image = new IdleImage(imagePath);
        this.hostname = hostname;
        this.versions = versions;
        this.console = console;
    }

    /** The framebuffer idle screen for {@code /dev/fb0}, if it exists and this user may write to it. */
    static Optional<IdleScreen.Display> open(Path imagePath, String hostname, String versions, boolean animate) {
        Path device = Path.of("/dev/fb0");
        if (!Files.exists(device)) {
            LOG.info("no display server and no /dev/fb0; the idle screen is disabled");
            return Optional.empty();
        }
        if (!Files.isWritable(device)) {
            LOG.warning("cannot write /dev/fb0, so the idle screen is disabled; add this user to the 'video' group"
                    + " (sudo usermod -aG video $USER) and log in again");
            return Optional.empty();
        }
        Optional<Geometry> geometry = geometry(Path.of("/sys/class/graphics/fb0"));
        if (geometry.isEmpty()) {
            LOG.warning("cannot read the size of /dev/fb0; the idle screen is disabled");
            return Optional.empty();
        }
        LOG.info(() -> "showing the idle screen on /dev/fb0 (" + geometry.get().width() + "×" + geometry.get().height()
                + ", " + geometry.get().bitsPerPixel() + " bpp)");
        Optional<Path> console = console(Path.of("/proc/self/fd"));
        LOG.info(() -> console.map(tty -> "hiding the console cursor on " + tty)
                .orElse("not running on a text console; the console cursor is left alone"));
        restoreCursorOnExit(console);
        return Optional.of(new FramebufferScreen(device, geometry.get(), imagePath, hostname, versions, console,
                animate));
    }

    /** Reads the framebuffer's geometry from its sysfs directory. */
    static Optional<Geometry> geometry(Path sysfs) {
        try {
            int bpp = Integer.parseInt(Files.readString(sysfs.resolve("bits_per_pixel")).strip());
            int stride = Integer.parseInt(Files.readString(sysfs.resolve("stride")).strip());
            // The visible mode; virtual_size can be larger (e.g. double the height for page flipping).
            Matcher m = MODE.matcher(readOr(sysfs.resolve("modes"), ""));
            if (!m.find()) {
                m = MODE.matcher(readOr(sysfs.resolve("virtual_size"), "").replace(',', 'x'));
                if (!m.find()) {
                    return Optional.empty();
                }
            }
            int width = Integer.parseInt(m.group(1));
            int height = Integer.parseInt(m.group(2));
            if ((bpp != 32 && bpp != 16) || stride < width * bpp / 8) {
                LOG.warning(() -> "unsupported framebuffer format: " + bpp + " bpp, stride " + stride);
                return Optional.empty();
            }
            return Optional.of(new Geometry(width, height, bpp, stride));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public void show(String status) {
        show(status, null);
    }

    @Override
    public void show(String status, AppMenu menu) {
        painter.execute(() -> {
            try {
                if (!cursorHidden) {
                    hideConsoleCursor();
                    cursorHidden = true;
                }
                showing = true;
                boolean imageChanged = image.reloadIfChanged();
                if (frame == null || imageChanged || !status.equals(renderedStatus)
                        || !Objects.equals(menu, renderedMenu)) {
                    base = render(status, menu);
                    frame = encode(base, geometry);
                    if (remote) {
                        drawRemote();
                    }
                    renderedStatus = status;
                    renderedMenu = menu;
                }
                // The list takes the logo's place.
                if (image.image() == null && menu == null) {
                    drawLogo();
                    startAnimation();
                } else {
                    stopAnimation();
                }
                write(frame);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "cannot draw the idle screen on " + device, e);
            }
        });
    }

    /** Draws the remote, lit or not, over {@link #base} into {@link #frame}. */
    private void drawRemote() {
        Rectangle region = IdleScreen.remoteRegion(geometry.width(), geometry.height());
        BufferedImage area = IdleScreen.copy(base, region);
        Graphics2D g = area.createGraphics();
        g.translate(-region.x, -region.y);
        IdleScreen.paintRemote(g, geometry.width(), geometry.height(), remoteLit);
        g.dispose();
        encodeInto(frame, area, region, geometry);
    }

    @Override
    public void flashRemote() {
        painter.execute(() -> {
            if (unlight != null) {
                // Another key while lit keeps it lit.
                unlight.cancel(false);
            }
            if (!remoteLit) {
                remoteLit = true;
                redrawRemote();
            }
            unlight = painter.schedule(() -> {
                unlight = null;
                remoteLit = false;
                redrawRemote();
            }, IdleScreen.FLASH.toMillis(), TimeUnit.MILLISECONDS);
        });
    }

    /** Writes the remote's box again, while the idle screen shows (never over a stream's picture). */
    private void redrawRemote() {
        if (!showing || !remote || frame == null) {
            return;
        }
        try {
            drawRemote();
            writeRows(IdleScreen.remoteRegion(geometry.width(), geometry.height()));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "cannot light the remote on " + device, e);
        }
    }

    /** Draws the logo, with its effects at the current moment, into {@link #frame}. */
    private void drawLogo() {
        Rectangle region = LogoEffects.region(geometry.width(), geometry.height());
        BufferedImage area = IdleScreen.copy(base, region);
        LogoEffects.paint(area, region, geometry.width(), geometry.height(), (System.nanoTime() - started) / 1e9,
                animate);
        encodeInto(frame, area, region, geometry);
    }

    private void startAnimation() {
        if (animate && animation == null && !animationFailed) {
            long period = 1000 / LogoEffects.FPS;
            animation = painter.scheduleAtFixedRate(this::animationFrame, period, period, TimeUnit.MILLISECONDS);
        }
    }

    private void stopAnimation() {
        if (animation != null) {
            animation.cancel(false);
            animation = null;
        }
    }

    /** Redraws only the logo's region and writes just those rows. */
    private void animationFrame() {
        if (!showing || image.image() != null || renderedMenu != null || frame == null) {
            return;
        }
        try {
            drawLogo();
            writeRows(LogoEffects.region(geometry.width(), geometry.height()));
        } catch (IOException | RuntimeException e) {
            // Keep the idle screen itself; just stop animating rather than failing 15 times a second.
            animationFailed = true;
            stopAnimation();
            LOG.log(Level.WARNING, "cannot animate the idle screen on " + device + "; showing it still", e);
        }
    }

    /** Writes just {@code region} of {@link #frame} to the framebuffer, row by row. */
    private void writeRows(Rectangle region) throws IOException {
        int bytesPerPixel = geometry.bitsPerPixel() / 8;
        try (FileChannel fb = FileChannel.open(device, StandardOpenOption.WRITE)) {
            for (int y = region.y; y < region.y + region.height; y++) {
                int offset = y * geometry.stride() + region.x * bytesPerPixel;
                ByteBuffer row = frame.duplicate().limit(offset + region.width * bytesPerPixel).position(offset);
                long position = offset;
                while (row.hasRemaining()) {
                    position += fb.write(row, position);
                }
            }
        }
    }

    @Override
    public void setVersions(String versions) {
        painter.execute(() -> {
            this.versions = versions;
            frame = null;
        });
    }

    @Override
    public void setRemote(boolean remote) {
        painter.execute(() -> {
            if (this.remote != remote) {
                this.remote = remote;
                frame = null;
            }
        });
    }

    @Override
    public void hide() {
        // Moonlight owns the display while streaming; just stop redrawing, and hide the cursor again afterwards.
        painter.execute(() -> {
            showing = false;
            cursorHidden = false;
            stopAnimation();
        });
    }

    /** Waits until queued drawing is done (for tests). */
    void awaitDrawn() throws Exception {
        painter.submit(() -> { }).get();
    }

    private BufferedImage render(String status, AppMenu menu) {
        BufferedImage out = new BufferedImage(geometry.width(), geometry.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            IdleScreen.paintBase(g, geometry.width(), geometry.height(), image.image(), hostname, status, versions);
            if (menu != null) {
                IdleScreen.paintMenu(g, geometry.width(), geometry.height(), menu);
            }
        } finally {
            g.dispose();
        }
        // The remote goes into the frame by itself (drawRemote), so a flash redraws only its box.
        return out;
    }

    /**
     * Converts an image to the framebuffer's memory layout: 32 bpp as little-endian XRGB8888 (bytes B, G, R, X) or
     * 16 bpp as little-endian RGB565, each line padded to the stride.
     */
    static ByteBuffer encode(BufferedImage img, Geometry g) {
        ByteBuffer buf = ByteBuffer.allocate(g.stride() * g.height());
        encodeInto(buf, img, new Rectangle(0, 0, g.width(), g.height()), g);
        buf.clear();
        return buf;
    }

    /** Encodes {@code area}, which shows {@code region} of the screen, into that region of a whole-screen buffer. */
    static void encodeInto(ByteBuffer buf, BufferedImage area, Rectangle region, Geometry g) {
        int[] row = new int[region.width];
        for (int y = 0; y < region.height; y++) {
            int sy = region.y + y;
            area.getRGB(0, y, region.width, 1, row, 0, region.width);
            buf.position(sy * g.stride() + region.x * (g.bitsPerPixel() / 8));
            for (int x = 0; x < row.length; x++) {
                int rgb = row[x];
                if (g.bitsPerPixel() == 32) {
                    buf.put((byte) rgb).put((byte) (rgb >> 8)).put((byte) (rgb >> 16)).put((byte) 0xFF);
                } else {
                    // 16 bits leave few shades per channel; dithering keeps gradients from turning into bands.
                    int sx = region.x + x;
                    int r = Dither.reduce((rgb >> 16) & 0xFF, 5, sx, sy);
                    int gr = Dither.reduce((rgb >> 8) & 0xFF, 6, sx, sy);
                    int b = Dither.reduce(rgb & 0xFF, 5, sx, sy);
                    int v = (r << 11) | (gr << 5) | b;
                    buf.put((byte) v).put((byte) (v >> 8));
                }
            }
        }
    }

    private void write(ByteBuffer data) throws IOException {
        try (FileChannel fb = FileChannel.open(device, StandardOpenOption.WRITE)) {
            // The whole buffer, wherever encoding the logo's region left its position.
            ByteBuffer view = data.duplicate().clear();
            long position = 0;
            while (view.hasRemaining()) {
                position += fb.write(view, position);
            }
        }
    }

    /**
     * The text console this agent runs on, e.g. {@code /dev/tty1}: whichever of its standard streams is connected
     * to one. Under the tty1 setup output goes to a log file, but input is still the console.
     *
     * @param fds the process's fd directory, {@code /proc/self/fd}
     */
    static Optional<Path> console(Path fds) {
        for (String fd : new String[] {"0", "1", "2"}) {
            try {
                Path target = Files.readSymbolicLink(fds.resolve(fd));
                if (target.toString().matches("/dev/tty\\d+")) {
                    return Optional.of(target);
                }
            } catch (IOException | UnsupportedOperationException e) {
                // not a link, or not Linux
            }
        }
        return Optional.empty();
    }

    /**
     * Stops the kernel drawing its console (the blinking cursor, text) over the framebuffer: switches the console to
     * graphics mode, and hides the cursor as a fallback. Needed again after every stream, because moonlight-qt puts
     * the console back into text mode when it exits.
     */
    private void hideConsoleCursor() {
        console.ifPresent(tty -> {
            boolean graphics = ConsoleMode.setGraphics(tty, true);
            if (graphicsModeLogged != Boolean.valueOf(graphics)) {
                graphicsModeLogged = graphics;
                if (graphics) {
                    LOG.info(() -> "switched " + tty + " to graphics mode, so no console cursor or text shows");
                } else {
                    LOG.warning(() -> "cannot switch " + tty + " to graphics mode; only hiding its cursor");
                }
            }
            try (OutputStream out = new FileOutputStream(tty.toFile(), true)) {
                out.write("\033[?25l".getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) {
                LOG.log(Level.FINE, "cannot hide the cursor on " + tty, e);
            }
        });
    }

    /** Puts the console back to text mode with a cursor when the agent exits, so a login on it works. */
    private static void restoreCursorOnExit(Optional<Path> console) {
        console.ifPresent(tty -> Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ConsoleMode.setGraphics(tty, false);
            try (OutputStream out = new FileOutputStream(tty.toFile(), true)) {
                out.write("\033[?25h".getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) {
                // exiting anyway
            }
        })));
    }

    private static String readOr(Path file, String fallback) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return fallback;
        }
    }
}
