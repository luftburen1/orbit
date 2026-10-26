package orbit.host;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** The label-and-field layout of Orbit's small dialogs. */
final class Forms {

    private Forms() {
    }

    /** One row per label and field: {@code form("Width", widthField, "Height", heightField)}. */
    static JPanel form(Object... labelsAndFields) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        for (int i = 0; i < labelsAndFields.length; i += 2) {
            c.gridy = i / 2;
            c.gridx = 0;
            c.fill = GridBagConstraints.NONE;
            c.weightx = 0;
            panel.add(new JLabel((String) labelsAndFields[i]), c);
            c.gridx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            panel.add((JComponent) labelsAndFields[i + 1], c);
        }
        return panel;
    }
}
