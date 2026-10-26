package orbit.host;

import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.Timer;

/**
 * What {@link MainWindow}'s client actions share: the frame their dialogs open over, the status bar, the clients'
 * activities, and refreshing the client list. All methods run on the Swing event thread.
 */
final class WindowSupport {

    final JFrame frame;
    final ClientActivities activities;
    private final JLabel statusBar;
    private final Runnable refresh;
    private final Consumer<ClientView> focus;
    private final Consumer<String> select;

    /**
     * @param focus  shows the window with the client selected
     * @param select selects the row with this id
     */
    WindowSupport(JFrame frame, ClientActivities activities, JLabel statusBar, Runnable refresh,
            Consumer<ClientView> focus, Consumer<String> select) {
        this.frame = frame;
        this.activities = activities;
        this.statusBar = statusBar;
        this.refresh = refresh;
        this.focus = focus;
        this.select = select;
    }

    void refresh() {
        refresh.run();
    }

    /** Shows the window with the client selected, so a dialog about it opens in context, e.g. from the tray. */
    void focus(ClientView client) {
        focus.accept(client);
    }

    void select(String id) {
        select.accept(id);
    }

    void status(String text) {
        statusBar.setText(text);
    }

    /** Starts the client's {@code activity} and shows it; false if the client is already busy. */
    boolean begin(String id, String activity) {
        if (!activities.begin(id, activity)) {
            return false;
        }
        refresh();
        return true;
    }

    /** Ends the client's {@code activity} after 5 s if its status update hasn't already. */
    void clearLater(String id, String activity) {
        clearLater(id, activity, 5000);
    }

    void clearLater(String id, String activity, int millis) {
        Timer fallback = new Timer(millis, e -> {
            if (activities.end(id, activity)) {
                refresh();
            }
        });
        fallback.setRepeats(false);
        fallback.start();
    }

    /** Says in the status bar that {@code what} failed, and why in a dialog. */
    void failed(String what, Throwable t) {
        status(what + " failed.");
        error(what + " failed", t);
    }

    void error(String title, Throwable t) {
        JOptionPane.showMessageDialog(frame, errorMessage(t), title, JOptionPane.ERROR_MESSAGE);
    }

    static String errorMessage(Throwable t) {
        return t instanceof TimeoutException || t.getCause() instanceof TimeoutException
                ? "The client did not answer in time." : AgentRegistry.rootMessage(t);
    }
}
