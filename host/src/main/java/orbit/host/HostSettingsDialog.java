package orbit.host;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.border.Border;

/**
 * Orbit's own settings ({@link HostConfig}), one page per topic in a list on the left. A client's stream settings are
 * in {@link SettingsDialog} instead. Event thread only.
 */
final class HostSettingsDialog {

    enum Page {
        GENERAL("General"), SUNSHINE("Sunshine"), MONITOR("Monitor"), STREAM("Stream");

        final String title;

        Page(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /** What this desktop lets Orbit do, so settings it can't apply are greyed out with the reason. */
    record Supports(String desktop, boolean display, boolean windows, boolean notices, boolean screenLock) {
        static Supports of(HostDesktop desktop) {
            return new Supports(desktop.name(), desktop.display().isPresent(), desktop.windows().isPresent(),
                    desktop.notices().isPresent(), desktop.screenLock().isPresent());
        }
    }

    private HostSettingsDialog() {
    }

    /**
     * Shows the settings at {@code page}, and saves and applies them on OK. A changed Sunshine login is checked
     * first; if Sunshine refuses it, Orbit asks before saving it anyway.
     *
     * @return what to show in the status bar after saving, or null if the dialog was cancelled
     */
    static String show(Component parent, OrbitHost host, Page page) {
        HostConfig old = host.config();
        Form form = new Form(old, Supports.of(host.desktop()), localHostname());
        form.select(page);
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent) != null
                ? SwingUtilities.getWindowAncestor(parent) : parent instanceof Window w ? w : null;
        JDialog dialog = new JDialog(owner, "Settings", JDialog.ModalityType.APPLICATION_MODAL);
        String[] result = {null};

        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        JLabel checking = new JLabel();
        checking.setForeground(UIManager.getColor("Label.disabledForeground"));
        cancel.addActionListener(e -> dialog.dispose());
        ok.addActionListener(e -> {
            HostConfig updated;
            try {
                updated = form.config();
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(dialog, ex.getMessage(), "Settings", JOptionPane.ERROR_MESSAGE);
                return;
            }
            Runnable save = () -> {
                try {
                    host.applySettings(updated);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog, AgentRegistry.rootMessage(ex), "Cannot save the settings",
                            JOptionPane.ERROR_MESSAGE);
                    return;
                }
                result[0] = savedNote(old, updated);
                dialog.dispose();
            };
            if (updated.sunshine().equals(old.sunshine()) || !updated.hasSunshineCredentials()) {
                save.run();
                return;
            }
            ok.setEnabled(false);
            checking.setText("Checking the Sunshine login…");
            checkLogin(updated.sunshine(), error -> {
                ok.setEnabled(true);
                checking.setText(" ");
                if (error == null || JOptionPane.showConfirmDialog(dialog,
                        "Sunshine answered: " + error + "\n\nSave this login anyway?", "Sunshine login",
                        JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
                    save.run();
                }
            });
        });
        form.testLogin.addActionListener(e -> {
            HostConfig.Sunshine login;
            try {
                login = form.sunshine();
            } catch (IllegalArgumentException ex) {
                form.testResult.setText(ex.getMessage());
                return;
            }
            form.testLogin.setEnabled(false);
            form.testResult.setText("Checking…");
            checkLogin(login, error -> {
                form.testLogin.setEnabled(true);
                form.testResult.setText(error == null ? "The login works." : "Sunshine answered: " + error);
            });
        });

        JPanel content = new JPanel(new BorderLayout());
        content.add(form.panel, BorderLayout.CENTER);
        content.add(buttonRow(checking, ok, cancel), BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(ok);
        dialog.pack();
        // The packed size fits the largest page; smaller would cut it off.
        dialog.setMinimumSize(dialog.getSize());
        dialog.setLocationRelativeTo(parent);
        loadMonitor(host, form);
        dialog.setVisible(true);
        return result[0];
    }

    /**
     * {@code note} and the buttons at the bottom right, spaced like a {@link JOptionPane}'s (as the client settings
     * are): its margin around them, its gap between them, and the same width for each.
     */
    static JPanel buttonRow(JLabel note, JButton... buttons) {
        Insets margin = UIManager.getBorder("OptionPane.border") instanceof Border b
                ? b.getBorderInsets(note) : new Insets(12, 12, 12, 12);
        int gap = UIManager.getInt("OptionPane.buttonPadding") > 0 ? UIManager.getInt("OptionPane.buttonPadding") : 8;
        int width = 0;
        for (JButton button : buttons) {
            width = Math.max(width, button.getPreferredSize().width);
        }
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        // The page above ends in its own margin.
        row.setBorder(BorderFactory.createEmptyBorder(0, margin.left, margin.bottom, margin.right));
        row.add(Box.createHorizontalGlue());
        row.add(note);
        for (JButton button : buttons) {
            Dimension size = new Dimension(width, button.getPreferredSize().height);
            button.setPreferredSize(size);
            button.setMaximumSize(size);
            row.add(Box.createHorizontalStrut(gap));
            row.add(button);
        }
        return row;
    }

    /** The status bar's line after saving: what takes a restart, or that it's saved. */
    static String savedNote(HostConfig old, HostConfig updated) {
        boolean restart = old.port() != updated.port()
                || !Objects.equals(old.advertiseName(), updated.advertiseName());
        return restart ? "Settings saved. The port and name apply when Orbit starts again." : "Settings saved.";
    }

    /** Asks Sunshine for its apps with {@code login}; {@code then} gets null if that worked, else Sunshine's answer. */
    private static void checkLogin(HostConfig.Sunshine login, Consumer<String> then) {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                new SunshineClient(login.uri(), login.user(), login.password()).listApps();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    then.accept(null);
                } catch (Exception e) {
                    then.accept(AgentRegistry.rootMessage(e));
                }
            }
        }.execute();
    }

    /**
     * Fills in Sunshine's monitor and whether it can be turned off, in the background. It's in Sunshine's config, so
     * it's unknown while Sunshine doesn't answer or has no login.
     */
    private static void loadMonitor(OrbitHost host, Form form) {
        StreamDirector director = host.director();
        boolean login = host.sunshine() != null;
        new SwingWorker<String[], Void>() {
            @Override
            protected String[] doInBackground() throws Exception {
                String output = director.sunshineMonitor().orElse(null);
                return new String[] {output, output != null ? director.turnOffRefusal().orElse(null) : null};
            }

            @Override
            protected void done() {
                try {
                    String[] found = get();
                    form.showMonitor(found[0], found[1],
                            login ? "Sunshine doesn't say." : "The Sunshine login isn't set up.");
                } catch (Exception e) {
                    String message = AgentRegistry.rootMessage(e);
                    form.showMonitor(null, null, (message.startsWith("Sunshine") ? message : "Sunshine: " + message)
                            + ".");
                }
            }
        }.execute();
    }

    private static String localHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "this computer's name";
        }
    }

    /** The pages and their fields, filled from a {@link HostConfig}. */
    static final class Form {
        final JPanel panel = new JPanel(new BorderLayout());
        final JButton testLogin = new JButton("Test login");
        final JLabel testResult = new JLabel(" ");
        private final JList<Page> pages = new JList<>(Page.values());
        private final CardLayout cards = new CardLayout();
        private final JPanel cardPanel = new JPanel(cards);
        private final HostConfig base;

        private final JComboBox<ThemeSwitcher.Choice> theme = new JComboBox<>(ThemeSwitcher.Choice.values());
        private final JSpinner port;
        private final JTextField name;

        private final JTextField url;
        private final JTextField user;
        private final JPasswordField password;

        private final JLabel monitor = new JLabel("Sunshine's monitor: …");
        private final JLabel monitorNote = note("Switch it in the Monitor menu.");
        private final JCheckBox matchResolution = new JCheckBox(
                "Set it to the client's resolution and frame rate during a stream");
        private final JCheckBox turnOff = new JCheckBox("Turn it off while nothing streams");
        private final JCheckBox startInTray = new JCheckBox("Start minimized to the tray");
        private final JLabel turnOffNote = note("It's turned on again before a stream. Never the primary monitor.");
        private final boolean display;

        private final JCheckBox placeWindows = new JCheckBox("Move the app's windows to Sunshine's monitor");
        private final JCheckBox launchSplash = new JCheckBox("Show \"Starting <app>\" there until the app is up");
        private final JCheckBox startNotice = new JCheckBox(
                "Ask whoever is at this computer to leave mouse and keyboard alone meanwhile");
        private final JCheckBox hideIdleCursor = new JCheckBox("Hide the cursor while a TV remote is in key mode");
        private final JCheckBox remoteNotices = new JCheckBox(
                "Show a TV remote's mode switches and Stop on Sunshine's monitor");
        private final JCheckBox unlockScreen = new JCheckBox("Unlock the screen for a stream when it's locked");

        /** @param hostname what agents see when no name is set */
        Form(HostConfig c, Supports supports, String hostname) {
            this.base = c;
            this.display = supports.display();

            theme.setSelectedItem(ThemeSwitcher.Choice.parse(c.theme()));
            theme.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                        boolean selected, boolean focus) {
                    String label = switch ((ThemeSwitcher.Choice) value) {
                        case SYSTEM -> "Follow system";
                        case LIGHT -> "Light";
                        case DARK -> "Dark";
                    };
                    return super.getListCellRendererComponent(list, label, index, selected, focus);
                }
            });
            port = new JSpinner(new SpinnerNumberModel(c.port(), 1, 65535, 1));
            port.setEditor(new JSpinner.NumberEditor(port, "#"));
            name = new JTextField(c.advertiseName() != null ? c.advertiseName() : "", 20);
            name.putClientProperty("JTextField.placeholderText", hostname);
            startInTray.setSelected(c.startInTray());
            page(Page.GENERAL,
                    Forms.form("Theme", theme, "Agent port", port, "Name agents see", name),
                    note("Agents find Orbit on the network by this name, or connect to the port directly."),
                    note("The port and name apply when Orbit starts again."), Box.createVerticalStrut(8),
                    startInTray, indented(note("Without a tray icon or a Sunshine login, the window opens anyway.")));

            url = new JTextField(c.sunshine().url(), 24);
            user = new JTextField(c.sunshine().user() != null ? c.sunshine().user() : "", 24);
            password = new JPasswordField(c.sunshine().password() != null ? c.sunshine().password() : "", 24);
            JPanel test = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            test.add(testLogin);
            test.add(Box.createHorizontalStrut(8));
            test.add(testResult);
            page(Page.SUNSHINE,
                    Forms.form("Web UI address", url, "Username", user, "Password", password, "", test),
                    note("Sunshine's web UI login, for pairing clients, listing apps and switching its monitor."));

            matchResolution.setSelected(c.monitor().matchResolution());
            turnOff.setSelected(c.monitor().turnOffWhenIdle());
            matchResolution.setEnabled(display);
            turnOff.setEnabled(false);
            page(Page.MONITOR, monitor, monitorNote, Box.createVerticalStrut(8),
                    matchResolution, indented(note("Its own mode comes back once nothing streams.")),
                    turnOff, indented(turnOffNote),
                    display ? null : note("Orbit can't change monitors on this desktop (" + supports.desktop() + ")."));

            HostConfig.Stream s = c.stream();
            placeWindows.setSelected(s.placeWindows());
            launchSplash.setSelected(s.launchSplash());
            startNotice.setSelected(s.startNotice());
            hideIdleCursor.setSelected(s.hideIdleCursor());
            remoteNotices.setSelected(s.remoteNotices());
            unlockScreen.setSelected(s.unlockScreen());
            placeWindows.setEnabled(supports.windows());
            hideIdleCursor.setEnabled(supports.windows());
            remoteNotices.setEnabled(supports.notices());
            unlockScreen.setEnabled(supports.screenLock());
            boolean all = supports.windows() && supports.notices() && supports.screenLock();
            boolean some = supports.windows() || supports.notices() || supports.screenLock();
            placeWindows.addActionListener(e -> followPlacement());
            followPlacement();
            page(Page.STREAM,
                    placeWindows,
                    indented(note("With focus and the cursor, so the stream shows the app and the remote's keys"
                            + " reach it.")),
                    indented(launchSplash), indented(startNotice),
                    hideIdleCursor, remoteNotices, Box.createVerticalStrut(8),
                    unlockScreen,
                    indented(note("Without its password, and on every monitor: anyone at this computer can use it.")),
                    indented(note("Orbit doesn't lock it again; Plasma's automatic screen lock does, if it's on.")),
                    all ? null : note((some ? "Some of these aren't" : "These aren't")
                            + " available on this desktop (" + supports.desktop() + ")."));

            pages.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            pages.setSelectedIndex(0);
            pages.addListSelectionListener(e -> {
                if (pages.getSelectedValue() != null) {
                    cards.show(cardPanel, pages.getSelectedValue().name());
                }
            });
            pages.setCellRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                        boolean selected, boolean focus) {
                    JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, false);
                    label.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 24));
                    return label;
                }
            });
            JScrollPane list = new JScrollPane(pages);
            list.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, UIManager.getColor("Component.borderColor")));
            panel.add(list, BorderLayout.WEST);
            panel.add(cardPanel, BorderLayout.CENTER);
        }

        void select(Page page) {
            pages.setSelectedValue(page, true);
        }

        /**
         * Sunshine's monitor, or null if unknown, and why it can't be turned off (e.g. it's the primary), or null if
         * it can. Turning off stays allowed to switch off if it was on. While the monitor is unknown both switches
         * stay editable: they apply once it's known, and the primary is never turned off anyway.
         *
         * @param unknown why the monitor is unknown, used when {@code output} is null
         */
        void showMonitor(String output, String refusal, String unknown) {
            monitor.setText(output != null ? "Sunshine streams " + output + "." : "Sunshine's monitor is unknown.");
            if (output == null) {
                monitorNote.setText(unknown);
            }
            turnOff.setText(output != null ? "Turn " + output + " off while nothing streams"
                    : "Turn it off while nothing streams");
            turnOff.setEnabled(display && (refusal == null || turnOff.isSelected()));
            if (refusal != null && display) {
                turnOffNote.setText("Not now: " + refusal + ".");
            }
        }

        /** The splash and the notice come with placing the app's windows: the KWin script puts them in place. */
        private void followPlacement() {
            boolean on = placeWindows.isEnabled() && placeWindows.isSelected();
            launchSplash.setEnabled(on);
            startNotice.setEnabled(on);
        }

        /** The Sunshine fields as a login. */
        HostConfig.Sunshine sunshine() {
            String address = url.getText().strip();
            URI uri;
            try {
                uri = URI.create(address);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("The Sunshine address isn't a valid URL.");
            }
            if (uri.getHost() == null || !List.of("http", "https").contains(uri.getScheme())) {
                throw new IllegalArgumentException(
                        "The Sunshine address needs to be a web address, like " + HostConfig.DEFAULT_SUNSHINE_URL + ".");
            }
            String u = user.getText().strip();
            return new HostConfig.Sunshine(address, u.isEmpty() ? null : u, u.isEmpty() ? null
                    : new String(password.getPassword()));
        }

        /** The settings as filled in; throws with a message for the user if something isn't valid. */
        HostConfig config() {
            // Unchanged, so the saved value stays absent if it was.
            String themeId = ((ThemeSwitcher.Choice) theme.getSelectedItem()).id();
            if (base.theme() == null && themeId.equals(ThemeSwitcher.Choice.SYSTEM.id())) {
                themeId = null;
            }
            return new HostConfig((Integer) port.getValue(), name.getText(), themeId, sunshine(),
                    new HostConfig.Monitor(turnOff.isSelected(), matchResolution.isSelected()),
                    new HostConfig.Stream(placeWindows.isSelected(), launchSplash.isSelected(),
                            startNotice.isSelected(), hideIdleCursor.isSelected(), remoteNotices.isSelected(),
                            unlockScreen.isSelected()),
                    startInTray.isSelected());
        }

        /** A page of rows from the top, each the full width; null rows are left out. */
        private void page(Page page, Component... rows) {
            JPanel p = new JPanel(new GridBagLayout());
            p.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.weightx = 1;
            c.anchor = GridBagConstraints.NORTHWEST;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(2, 0, 2, 0);
            for (Component row : rows) {
                if (row != null) {
                    p.add(row, c);
                }
            }
            c.weighty = 1;
            p.add(Box.createGlue(), c);
            JPanel titled = new JPanel(new BorderLayout());
            JLabel title = new JLabel(page.title);
            title.putClientProperty("FlatLaf.styleClass", "h3");
            title.setBorder(BorderFactory.createEmptyBorder(12, 16, 0, 16));
            titled.add(title, BorderLayout.NORTH);
            titled.add(p, BorderLayout.CENTER);
            cardPanel.add(titled, page.name());
        }

        private static JLabel note(String text) {
            JLabel label = new JLabel(text);
            label.setForeground(UIManager.getColor("Label.disabledForeground"));
            return label;
        }

        private static JComponent indented(JComponent c) {
            JPanel p = new JPanel(new BorderLayout());
            p.setBorder(BorderFactory.createEmptyBorder(0, 24, 0, 0));
            p.add(c, BorderLayout.CENTER);
            return p;
        }
    }
}
