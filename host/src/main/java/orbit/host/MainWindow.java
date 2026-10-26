package orbit.host;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/** Client list with actions. All methods run on the Swing event thread. */
final class MainWindow implements TrayController.Window {

    private static final String STARTING = ClientActivities.STARTING;
    private static final String STOPPING = ClientActivities.STOPPING;
    private static final String PAIRING = ClientActivities.PAIRING;
    private static final String UNPAIRING = ClientActivities.UNPAIRING;
    private static final String RECONNECTING = ClientActivities.RECONNECTING;
    private static final Icon PAIR_ICON = Icons.link(18);
    private static final Icon UNPAIR_ICON = Icons.unlink(18);
    private static final Icon PLAY_ICON = Icons.play(18);
    private static final Icon STOP_ICON = Icons.stop(18);

    private final OrbitHost host;
    private final AgentRegistry registry;
    private final StreamDirector director;
    private final JFrame frame = new JFrame(AppInfo.NAME);
    private final ClientActivities activities = new ClientActivities();
    private final ClientTable table = new ClientTable(activities::get);
    private final JScrollPane tableScroll = new JScrollPane(table.component());
    private final JLabel statusBar = new JLabel(" ");
    private final RowFitter rowFitter = new RowFitter(frame, table.component(), tableScroll);
    private final WindowSupport ui =
            new WindowSupport(frame, activities, statusBar, this::refresh, this::focus, table::select);
    private final PairingActions pairing;
    private final StreamActions streams;
    /**
     * Repaints what pulses at about 60 fps: the dots next to "Streaming", and the selected client's Pair, Start or
     * Stop button while that action is in progress. Runs only while something pulses.
     */
    private final Timer pulse = new Timer(16, e -> repaintPulsing());
    /** False until the window has been sized to its content, on first showing. */
    private boolean sized;
    /** Created on first use. */
    private LogWindow logWindow;
    private final List<Runnable> changeListeners = new ArrayList<>();

    private final JButton approve = new JButton(Icons.check(18));
    /** Pair, or Unpair while Sunshine has the selected client paired. */
    private final JButton pair = new JButton(PAIR_ICON);
    /** Start, or Stop while the selected client streams: the two never apply at once. */
    private final JButton play = new JButton(PLAY_ICON);
    private final JButton settings = new JButton(Icons.cog(18));
    private final JButton remove = new JButton(Icons.trash(18));
    /** The button whose action is running, by the client's activity; it pulses while that client is selected. */
    private final Map<String, JButton> pulsingButton =
            Map.of(PAIRING, pair, UNPAIRING, pair, STARTING, play, RECONNECTING, play, STOPPING, play);

    MainWindow(OrbitHost host) {
        this.host = host;
        this.registry = host.registry();
        this.director = host.director();
        this.pairing = new PairingActions(host, ui, this::editSunshine);
        this.streams = new StreamActions(host, ui);

        table.component().getSelectionModel().addListSelectionListener(e -> {
            updateButtons();
            updatePulse();
        });
        table.component().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    table.selected().filter(c -> allowed(c).contains(ClientActions.Action.START))
                            .ifPresent(MainWindow.this::chooseAppAndStart);
                    table.selected().filter(c -> c.pairingRequest() != null && allowed(c).contains(
                            ClientActions.Action.PAIR)).ifPresent(pairing::pairRequest);
                }
            }
        });

        approve.addActionListener(e -> table.selected().ifPresent(this::approve));
        pair.addActionListener(e -> table.selected().ifPresent(c -> {
            if (allowed(c).contains(ClientActions.Action.UNPAIR)) {
                unpair(c);
            } else {
                pair(c);
            }
        }));
        play.addActionListener(e -> table.selected().ifPresent(c -> {
            if (allowed(c).contains(ClientActions.Action.STOP)) {
                stopStream(c);
            } else {
                chooseAppAndStart(c);
            }
        }));
        settings.addActionListener(e -> table.selected().ifPresent(this::editSettings));
        remove.addActionListener(e -> table.selected().ifPresent(this::remove));
        describe(approve, "Approve");
        describe(settings, "Stream settings");
        describe(remove, "Remove client");

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        for (JComponent c : List.of(approve, pair, play, settings, remove)) {
            toolbar.add(c);
        }

        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        frame.setJMenuBar(menuBar());
        frame.add(toolbar, BorderLayout.NORTH);
        frame.add(tableScroll, BorderLayout.CENTER);
        frame.add(statusBar, BorderLayout.SOUTH);
        frame.setIconImages(Icons.windowIcons());

        registry.addListener(new AgentRegistry.Listener() {
            @Override
            public void clientChanged(ClientView client) {
                SwingUtilities.invokeLater(MainWindow.this::refresh);
            }

            @Override
            public void clientRemoved(String agentId) {
                SwingUtilities.invokeLater(MainWindow.this::refresh);
            }

            @Override
            public void pairingsChanged() {
                SwingUtilities.invokeLater(MainWindow.this::refresh);
            }
        });
        director.addOwnerListener(() -> SwingUtilities.invokeLater(this::refresh));
        registry.setStreamRequestHandler(
                (client, app) -> SwingUtilities.invokeLater(() -> streams.startRequested(client, app)));
        registry.setStopRequestHandler(client -> SwingUtilities.invokeLater(() -> streams.stopRequested(client)));
        refresh();
    }

    /** ☰ (settings, log, quit), Monitor, and About at the right. */
    private JMenuBar menuBar() {
        JMenu file = new JMenu();
        file.setIcon(Icons.menu(18));
        describe(file, "Menu");
        JMenuItem settingsItem = new JMenuItem("Settings");
        settingsItem.addActionListener(e -> showSettings(HostSettingsDialog.Page.GENERAL));
        JMenuItem logItem = new JMenuItem("Show log");
        logItem.addActionListener(e -> showLog());
        JMenuItem quit = new JMenuItem("Quit");
        quit.addActionListener(e -> quit());
        file.add(settingsItem);
        file.add(logItem);
        file.addSeparator();
        file.add(quit);

        MonitorMenu monitors = new MonitorMenu(host, frame, new MonitorMenu.Owner() {
            @Override
            public void status(String text) {
                MainWindow.this.status(text);
            }

            @Override
            public void error(String title, Throwable cause) {
                ui.error(title, cause);
            }

            @Override
            public void editSunshine() {
                MainWindow.this.editSunshine();
            }

            @Override
            public void reconnecting(List<String> agentIds) {
                agentIds.forEach(id -> activities.begin(id, RECONNECTING));
                refresh();
            }

            @Override
            public void reconnected(List<String> agentIds) {
                agentIds.forEach(streams::reconnected);
                refresh();
            }
        });

        JButton about = new JButton(Icons.info(18));
        about.putClientProperty("JButton.buttonType", "toolBarButton");
        about.setFocusable(false);
        describe(about, "About Orbit");
        about.addActionListener(e -> showAbout());

        JMenuBar menu = new JMenuBar();
        menu.add(file);
        menu.add(monitors.component());
        menu.add(Box.createHorizontalGlue());
        menu.add(about);
        return menu;
    }

    /**
     * Shows the window, or leaves it hidden in the tray.
     *
     * @param exitOnClose true when there is no tray icon to bring the window back
     */
    void open(boolean exitOnClose) {
        frame.setDefaultCloseOperation(exitOnClose ? WindowConstants.EXIT_ON_CLOSE : WindowConstants.HIDE_ON_CLOSE);
        if (exitOnClose) {
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    host.stop();
                }
            });
        }
        // With a tray icon Orbit starts in the tray if so set, unless the Sunshine login still needs setting up.
        if (exitOnClose || host.sunshine() == null || !host.config().startInTray()) {
            show();
        }
        if (host.sunshine() == null) {
            status("Set up the Sunshine login (☰ ▸ Settings ▸ Sunshine) to pair clients and list apps.");
        }
    }

    @Override
    public void show() {
        if (!sized) {
            table.fitToContent();
            frame.pack();
            // Never smaller than its compact view: every column, and two rows.
            frame.setMinimumSize(new Dimension(frame.getWidth(), frame.getHeight() - table.heightBeyondCompact()));
            // Centred on the primary monitor the first time; after that it stays where it was moved.
            frame.setLocationRelativeTo(null);
            sized = true;
        } else {
            fitRows();
        }
        frame.setVisible(true);
        frame.setExtendedState(frame.getExtendedState() & ~JFrame.ICONIFIED);
        frame.toFront();
    }

    private void refresh() {
        List<ClientView> clients = registry.clients();
        List<ClientView> rows = new ArrayList<>(clients);
        rows.addAll(registry.pairingRequests());
        rows.addAll(registry.agentlessPairings());
        activities.settle(rows);
        // Only while it streams: the owner is cleared a few seconds after its stream ends.
        String owner = director.modeOwner();
        boolean ownerStreams = clients.stream().anyMatch(c -> c.agentId().equals(owner) && c.streaming());
        table.setClients(rows, ownerStreams ? owner : null);
        fitRows();
        updatePulse();
        updateButtons();
        changeListeners.forEach(Runnable::run);
    }

    private void fitRows() {
        if (sized) {
            rowFitter.fit();
        }
    }

    /** What the toolbar and a double-click may do with {@code c} right now. */
    private Set<ClientActions.Action> allowed(ClientView c) {
        return ClientActions.enabled(c, activities.busy(c.agentId()), streamLimit(c) == null);
    }

    private void updateButtons() {
        ClientView client = table.selected().orElse(null);
        Set<ClientActions.Action> allowed = client != null ? allowed(client) : Set.of();
        String activity = client != null ? activities.get(client.agentId()) : null;
        // Stop while streaming or stopping, else Start; it says why Start is greyed out when Sunshine's stream limit
        // is the only reason.
        boolean stopping = allowed.contains(ClientActions.Action.STOP) || STOPPING.equals(activity);
        String limit = client != null ? ClientActions.onlyStreamLimit(client, activity != null, streamLimit(client))
                : null;
        play.setIcon(stopping ? STOP_ICON : PLAY_ICON);
        describe(play, stopping ? "Stop stream" : limit != null ? "Start stream (" + limit + ")" : "Start stream");
        play.setEnabled(allowed.contains(ClientActions.Action.START) || allowed.contains(ClientActions.Action.STOP));
        approve.setEnabled(allowed.contains(ClientActions.Action.APPROVE));
        boolean unpairing = allowed.contains(ClientActions.Action.UNPAIR) || UNPAIRING.equals(activity);
        pair.setIcon(unpairing ? UNPAIR_ICON : PAIR_ICON);
        describe(pair, unpairing ? "Unpair (remove its pairing in Sunshine)" : "Pair");
        pair.setEnabled(allowed.contains(ClientActions.Action.PAIR) || allowed.contains(ClientActions.Action.UNPAIR));
        settings.setEnabled(allowed.contains(ClientActions.Action.SETTINGS));
        remove.setEnabled(allowed.contains(ClientActions.Action.REMOVE));
        // While its action runs, the disabled button shows its own icon pulsing; otherwise the usual grey.
        JButton busy = busyButton();
        for (JButton b : List.of(pair, play)) {
            b.setDisabledIcon(b == busy ? Icons.pulsing(b.getIcon()) : null);
        }
    }

    @Override
    public String streamLimit(ClientView c) {
        return streams.streamLimit(c);
    }

    @Override
    public String activity(String agentId) {
        return activities.get(agentId);
    }

    @Override
    public boolean setsHostMonitor(String agentId) {
        return agentId.equals(table.monitorOwner());
    }

    @Override
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    /** Shows the window with the client selected, so a dialog about it opens in context, e.g. from the tray. */
    private void focus(ClientView client) {
        if (!frame.isVisible() || (frame.getExtendedState() & JFrame.ICONIFIED) != 0) {
            show();
        }
        table.select(client.agentId());
    }

    // ---- pulsing ----

    /** The selected client's button whose action is running, or null. */
    private JButton busyButton() {
        return table.selected().map(c -> activities.get(c.agentId())).map(pulsingButton::get).orElse(null);
    }

    /** Runs the pulse timer while a client streams, starts or stops, or the selected client's action is running. */
    private void updatePulse() {
        boolean pulsing = table.anyPulsing() || busyButton() != null;
        if (pulsing && !pulse.isRunning()) {
            pulse.start();
        } else if (!pulsing) {
            pulse.stop();
        }
    }

    private void repaintPulsing() {
        if (!frame.isShowing()) {
            return;
        }
        // Painted now rather than queued, then flushed: on Linux, X11 buffers drawing, so frames otherwise reach
        // the screen in uneven bursts.
        table.paintPulsingIcons();
        JButton busy = busyButton();
        if (busy != null) {
            busy.paintImmediately(0, 0, busy.getWidth(), busy.getHeight());
        }
        Toolkit.getDefaultToolkit().sync();
    }

    // ---- menu actions ----

    private void showLog() {
        if (logWindow == null) {
            logWindow = new LogWindow(frame, ConsoleLog.get());
        }
        logWindow.show();
    }

    /** A modal box with Orbit's version and author. */
    private void showAbout() {
        aboutPane().createDialog(frame, "About " + AppInfo.NAME).setVisible(true);
    }

    private static JOptionPane aboutPane() {
        JLabel name = new JLabel(AppInfo.NAME);
        name.setFont(name.getFont().deriveFont(Font.BOLD, name.getFont().getSize2D() * 1.5f));
        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(name);
        text.add(Box.createVerticalStrut(4));
        text.add(new JLabel("Version " + AppInfo.VERSION));
        text.add(new JLabel(AppInfo.TAGLINE));
        text.add(Box.createVerticalStrut(8));
        text.add(new JLabel("By " + AppInfo.AUTHOR));
        return new JOptionPane(text, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION,
                new ImageIcon(Icons.logo(64)));
    }

    private void editSunshine() {
        showSettings(HostSettingsDialog.Page.SUNSHINE);
    }

    private void showSettings(HostSettingsDialog.Page page) {
        String saved = HostSettingsDialog.show(frame, host, page);
        if (saved != null) {
            status(saved);
            refresh();
        }
    }

    // ---- client actions (toolbar and tray) ----

    @Override
    public void quit() {
        System.exit(0);
    }

    @Override
    public void approve(ClientView client) {
        registry.approve(client.agentId());
    }

    @Override
    public void pair(ClientView client) {
        pairing.pair(client);
    }

    /** Asks, then removes the client's pairing in Sunshine and ends its stream, if any. */
    @Override
    public void unpair(ClientView client) {
        pairing.unpair(client);
    }

    @Override
    public void chooseAppAndStart(ClientView client) {
        streams.chooseAppAndStart(client);
    }

    @Override
    public void startStream(ClientView client, String app) {
        streams.startStream(client, app);
    }

    @Override
    public void stopStream(ClientView client) {
        streams.stopStream(client);
    }

    @Override
    public void editSettings(ClientView client) {
        streams.editSettings(client);
    }

    @Override
    public void remove(ClientView client) {
        pairing.remove(client);
    }

    /** Gives an icon-only button its tooltip and the name screen readers announce. */
    private static void describe(AbstractButton button, String name) {
        button.setToolTipText(name);
        button.getAccessibleContext().setAccessibleName(name);
    }

    private void status(String text) {
        ui.status(text);
    }
}
