package orbit.host;

import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JScrollPane;

/**
 * Makes the window taller when rows were added below the visible ones, e.g. a phone that wants to pair, as far as the
 * screen allows, and gives that height back as rows go. It never shrinks below what it was before, so a size chosen
 * by hand stays; nor does a maximized window change. Runs on the Swing event thread.
 */
final class RowFitter {

    private final JFrame frame;
    private final JComponent rows;
    private final JScrollPane scroll;
    /** Height {@link #fit} added for rows, which it gives back when they go; 0 once resized by hand. */
    private int grownBy;
    /** The height {@link #fit} last set, to tell when it was resized by hand since. */
    private int fittedHeight;

    /** @param rows the table inside {@code scroll} */
    RowFitter(JFrame frame, JComponent rows, JScrollPane scroll) {
        this.frame = frame;
        this.rows = rows;
        this.scroll = scroll;
    }

    void fit() {
        if ((frame.getExtendedState() & JFrame.MAXIMIZED_VERT) != 0) {
            return;
        }
        if (grownBy > 0 && frame.getHeight() != fittedHeight) {
            grownBy = 0; // resized by hand since: that's the size to keep
        }
        int missing = rows.getPreferredSize().height - scroll.getViewport().getExtentSize().height;
        if (missing < 0 && grownBy > 0) {
            int shrink = Math.min(-missing, grownBy);
            grownBy -= shrink;
            fittedHeight = frame.getHeight() - shrink;
            frame.setSize(frame.getWidth(), fittedHeight);
            frame.validate();
            return;
        }
        if (missing <= 0) {
            return;
        }
        Rectangle screen = frame.getGraphicsConfiguration().getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(frame.getGraphicsConfiguration());
        int maxHeight = screen.height - insets.top - insets.bottom;
        int height = Math.min(frame.getHeight() + missing, maxHeight);
        if (height <= frame.getHeight()) {
            return;
        }
        grownBy += height - frame.getHeight();
        fittedHeight = height;
        frame.setSize(frame.getWidth(), height);
        // Moved up as far as needed to stay on screen, where the window manager lets windows be placed.
        int bottom = screen.y + screen.height - insets.bottom;
        if (frame.getY() + height > bottom) {
            frame.setLocation(frame.getX(), Math.max(screen.y + insets.top, bottom - height));
        }
        frame.validate();
    }
}
