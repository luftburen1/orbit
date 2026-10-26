package orbit.host;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

/**
 * A banner on the host's own monitor while a stream's app starts, asking whoever sits there to leave the mouse and
 * keyboard alone: until the app's first window comes, the first window that opens or gets focus is taken for the app's
 * and moved to Sunshine's monitor. The desktop's {@link HostDesktop.Windows} knows the window by its {@link #TITLE}: it
 * puts it at the top of the main monitor (not Sunshine's), above other windows and out of the taskbar. It never takes
 * focus. It closes once the app's window is found, the start fails, the stream ends, or at the latest after
 * {@link WindowPlacement#FIRST_WINDOW_WITHIN}.
 */
final class StartNotice {

    static final String TITLE = "Orbit: starting a stream, hands off";

    /** Counts {@link #show} calls; a close covers every show before it, even one whose window hasn't opened yet. */
    private final AtomicLong shows = new AtomicLong();
    private final AtomicLong closedUpTo = new AtomicLong();
    // Event thread only.
    private JFrame frame;
    private Timer limit;

    /** Shows the notice for {@code app}; replaces one still showing. */
    void show(String app) {
        long show = shows.incrementAndGet();
        SwingUtilities.invokeLater(() -> {
            if (closedUpTo.get() >= show) {
                return;
            }
            dispose();
            open(app);
        });
    }

    /** Closes the notice, from any thread; nothing if none shows. */
    void close() {
        closedUpTo.accumulateAndGet(shows.get(), Math::max);
        SwingUtilities.invokeLater(this::dispose);
    }

    private void open(String app) {
        frame = new JFrame(TITLE);
        frame.setUndecorated(true);
        // Never focused, so it can't be what the stream's first window is taken from, nor take keys from anything.
        frame.setFocusableWindowState(false);
        frame.setAutoRequestFocus(false);
        frame.setContentPane(content(app));
        frame.pack();
        // The desktop's window placement puts it at the top of the main monitor as soon as it's mapped.
        frame.setVisible(true);
        limit = new Timer((int) WindowPlacement.FIRST_WINDOW_WITHIN.toMillis(), e -> dispose());
        limit.setRepeats(false);
        limit.start();
    }

    /** What the notice shows; theme colours, as it sits among the host's own windows. */
    static JComponent content(String app) {
        JLabel title = new JLabel("Starting " + app + " for a stream…");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() * 1.4f));
        JLabel ask = new JLabel("Please leave the mouse and keyboard alone until it has opened.");
        JLabel why = new JLabel("The next window that opens or gets focus here goes to the stream.");
        for (JLabel l : new JLabel[] {ask, why}) {
            l.setFont(l.getFont().deriveFont(l.getFont().getSize2D() * 1.1f));
        }
        JPanel lines = new JPanel(new GridLayout(2, 1, 0, 2));
        lines.setOpaque(false);
        lines.add(ask);
        lines.add(why);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(title, BorderLayout.NORTH);
        panel.add(lines, BorderLayout.CENTER);
        Color border = UIManager.getColor("Component.borderColor");
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border != null ? border : Color.GRAY),
                BorderFactory.createEmptyBorder(14, 20, 14, 20)));
        return panel;
    }

    private void dispose() {
        if (limit != null) {
            limit.stop();
            limit = null;
        }
        if (frame != null) {
            frame.dispose();
            frame = null;
        }
    }
}
