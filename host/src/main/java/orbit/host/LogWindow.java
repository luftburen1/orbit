package orbit.host;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.text.DefaultCaret;

/** Shows Orbit's console log and follows it while open. Event thread only. */
final class LogWindow {

    private final ConsoleLog log;
    private final JDialog dialog;
    private final JTextArea text = new JTextArea();
    private final JScrollPane scroll = new JScrollPane(text);
    private final Timer follow = new Timer(500, e -> refresh());
    private long shownVersion = -1;

    LogWindow(JFrame owner, ConsoleLog log) {
        this.log = log;
        dialog = new JDialog(owner, "Orbit log", false);
        text.setEditable(false);
        // The scroll position is set explicitly below; the caret must not move it.
        ((DefaultCaret) text.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, text.getFont().getSize()));

        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(log.text()), null));
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> log.clear());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        buttons.add(copy);
        buttons.add(clear);

        dialog.add(scroll, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        // Never smaller than a few lines of the log, at a width a line mostly fits, with the buttons below.
        text.setColumns(60);
        text.setRows(8);
        dialog.pack();
        dialog.setMinimumSize(dialog.getSize());
        dialog.setSize(new Dimension(900, 480));
        dialog.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentHidden(ComponentEvent e) {
                follow.stop();
            }
        });
    }

    void show() {
        if (!dialog.isVisible()) {
            dialog.setLocationRelativeTo(dialog.getOwner());
        }
        shownVersion = -1;
        refresh();
        follow.start();
        dialog.setVisible(true);
        dialog.toFront();
    }

    /** Redraws when the log changed, staying at the end unless the user scrolled up to read. */
    private void refresh() {
        long version = log.version();
        if (version == shownVersion) {
            return;
        }
        shownVersion = version;
        JScrollBar bar = scroll.getVerticalScrollBar();
        boolean atEnd = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 4;
        int position = bar.getValue();
        text.setText(log.text());
        SwingUtilities.invokeLater(() -> bar.setValue(atEnd ? bar.getMaximum() : position));
    }
}
