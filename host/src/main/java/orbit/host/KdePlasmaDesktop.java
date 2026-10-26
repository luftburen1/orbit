package orbit.host;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * KDE Plasma: monitor modes with {@code kscreen-doctor} ({@link KScreenDoctor}), window placement through KWin scripts
 * ({@link KWinWindows}), notices in plasmashell's on-screen display ({@link PlasmaOsd}) and the remote's stop question
 * ({@link StopQuestion}, placed where {@code kscreen-doctor} says Sunshine's monitor is), the colour scheme
 * ({@link KdeColorScheme}) and the screen lock, which kscreenlocker drops on logind's Unlock ({@link LogindScreenLock}).
 * Each part is there when its tool, D-Bus service or file is.
 */
final class KdePlasmaDesktop implements HostDesktop {

    private static final Logger LOG = Logger.getLogger(KdePlasmaDesktop.class.getName());

    /**
     * Plasma's own processes: windows they start don't make a stream app's family, or everything opened from the
     * desktop's menu would follow the stream.
     */
    static final Set<String> SHELL_PROCESSES = Set.of("plasmashell", "kwin_wayland", "kwin_x11", "ksmserver",
            "startplasma-wayland", "startplasma-x11", "krunner");

    private final DisplayControl display;
    private final KWinWindows windows;
    private final PlasmaOsd osd;
    private final KdeColorScheme colors;
    private final HostDesktop.ScreenLock screenLock;
    private final StopQuestion stopQuestion = new StopQuestion();
    private final Notices notices = new PlasmaNotices();

    private KdePlasmaDesktop(DisplayControl display, KWinWindows windows, PlasmaOsd osd, KdeColorScheme colors,
            HostDesktop.ScreenLock screenLock) {
        this.display = display;
        this.windows = windows;
        this.osd = osd;
        this.colors = colors;
        this.screenLock = screenLock;
    }

    /** Empty when none of Plasma's parts is there, e.g. on another desktop. */
    static Optional<HostDesktop> detect() {
        DisplayControl display = KScreenDoctor.find().orElse(null);
        KWinWindows windows = KWinWindows.connect(SHELL_PROCESSES).orElse(null);
        PlasmaOsd osd = PlasmaOsd.connect().orElse(null);
        KdeColorScheme colors = KdeColorScheme.find().orElse(null);
        if (display == null && windows == null && osd == null && colors == null) {
            return Optional.empty();
        }
        // logind runs on other desktops too, so it doesn't tell Plasma is there; only that its locker answers it.
        HostDesktop.ScreenLock screenLock = windows != null ? LogindScreenLock.find().orElse(null) : null;
        return Optional.of(new KdePlasmaDesktop(display, windows, osd, colors, screenLock));
    }

    @Override
    public String name() {
        return "KDE Plasma";
    }

    @Override
    public Optional<DisplayControl> display() {
        return Optional.ofNullable(display);
    }

    @Override
    public Optional<Windows> windows() {
        return Optional.ofNullable(windows);
    }

    @Override
    public Optional<Notices> notices() {
        return osd != null || display != null ? Optional.of(notices) : Optional.empty();
    }

    @Override
    public Optional<DesktopColors.Source> colors() {
        return Optional.ofNullable(colors);
    }

    @Override
    public Optional<HostDesktop.ScreenLock> screenLock() {
        return Optional.ofNullable(screenLock);
    }

    /** The on-screen display's notices, and the stop question where {@code kscreen-doctor} finds the monitor. */
    private final class PlasmaNotices implements Notices {
        @Override
        public void remoteAction(String action) {
            if (osd != null) {
                osd.remoteAction(action);
            }
        }

        @Override
        public boolean askToStop(String output, String quitsApp) {
            if (display == null) {
                return false;
            }
            Optional<Rectangle> bounds;
            try {
                bounds = display.bounds(output);
            } catch (IOException e) {
                LOG.log(Level.INFO, "cannot tell where " + output + " is; stopping without asking", e);
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            bounds.ifPresent(b -> stopQuestion.show(b, quitsApp));
            return bounds.isPresent();
        }

        @Override
        public void selectStop(boolean ok) {
            stopQuestion.select(ok);
        }

        @Override
        public void closeStopQuestion() {
            stopQuestion.close();
        }
    }

    @Override
    public void close() {
        stopQuestion.close();
        if (windows != null) {
            windows.close();
        }
        if (osd != null) {
            osd.close();
        }
        if (colors != null) {
            colors.close();
        }
    }
}
