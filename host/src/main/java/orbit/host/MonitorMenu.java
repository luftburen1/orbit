package orbit.host;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.SwingWorker;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

/**
 * The window's Monitor menu: which display Sunshine captures (switching restarts Sunshine and reconnects streaming
 * clients). Filled from Sunshine each time it opens. Whether that monitor turns off while nothing streams is in
 * {@link HostSettingsDialog}. Event thread only.
 */
final class MonitorMenu {

    private final JMenu menu = new JMenu();
    private final OrbitHost host;
    private final StreamDirector director;
    private final MonitorSwitcher switcher;
    private final Component parent;
    private final Owner owner;
    /** True while a monitor switch (and Sunshine restart) is in progress. */
    private boolean switching;

    /** What the menu needs from the window. Event thread only. */
    interface Owner {
        /** Shows a line in the window's status bar. */
        void status(String text);

        /** Shows an error dialog with a title. */
        void error(String title, Throwable cause);

        /** Opens the settings at the Sunshine login, offered when there is no login yet. */
        void editSunshine();

        /** These clients are about to be disconnected and reconnected; they show it until {@link #reconnected}. */
        void reconnecting(List<String> agentIds);

        /** The reconnect attempts are over; each client shows its own state again once it reports it. */
        void reconnected(List<String> agentIds);
    }

    /** @param parent for the dialogs it shows */
    MonitorMenu(OrbitHost host, Component parent, Owner owner) {
        this.host = host;
        this.director = host.director();
        this.switcher = new MonitorSwitcher(host.registry(), director);
        this.parent = parent;
        this.owner = owner;
        menu.setIcon(Icons.monitor(18));
        menu.setToolTipText("Monitor");
        menu.getAccessibleContext().setAccessibleName("Monitor");
        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                load();
            }

            @Override
            public void menuDeselected(MenuEvent e) {
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });
    }

    JMenu component() {
        return menu;
    }

    /** @param primary the desktop's primary monitor as a connector name, or null if unknown */
    private record Choice(List<SunshineClient.Monitor> monitors, String current, String primary) {
    }

    private void load() {
        SunshineClient sunshine = host.sunshine();
        if (sunshine == null) {
            JMenuItem setUp = new JMenuItem("Set up the Sunshine login first…");
            setUp.addActionListener(e -> owner.editSunshine());
            show(List.of(setUp));
            return;
        }
        if (switching) {
            show(List.of(disabled("Switching monitor; Sunshine is restarting…")));
            return;
        }
        show(List.of(disabled("Loading…")));
        new SwingWorker<Choice, Void>() {
            @Override
            protected Choice doInBackground() throws Exception {
                String primary = director.primaryMonitor().orElse(null);
                List<SunshineClient.Monitor> monitors = host.monitorMemory()
                        .complete(sunshine.listMonitors(), director.connectedMonitors());
                return new Choice(monitors, sunshine.currentMonitor(), primary);
            }

            @Override
            protected void done() {
                try {
                    showChoice(get());
                } catch (Exception e) {
                    String message = AgentRegistry.rootMessage(e);
                    show(List.of(disabled(message.startsWith("Sunshine") ? message : "Sunshine: " + message)));
                }
            }
        }.execute();
    }

    private void showChoice(Choice choice) {
        if (choice.monitors().isEmpty()) {
            show(List.of(disabled("Sunshine's log has no monitor list (capture method isn't KMS?)")));
            return;
        }
        ButtonGroup group = new ButtonGroup();
        List<JMenuItem> items = new ArrayList<>();
        JRadioButtonMenuItem automatic = new JRadioButtonMenuItem("Automatic (Sunshine default)",
                choice.current().isBlank());
        automatic.addActionListener(e -> switchTo("", "Sunshine's default monitor", choice.current()));
        group.add(automatic);
        items.add(automatic);
        for (SunshineClient.Monitor m : choice.monitors()) {
            boolean current = m.id().equals(choice.current()) || Integer.toString(m.index()).equals(choice.current());
            String primary = m.id().equals(choice.primary()) ? " (primary)" : "";
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(m.id() + " — " + m.description() + primary, current);
            item.addActionListener(e -> switchTo(m.id(), m.id(), choice.current()));
            group.add(item);
            items.add(item);
        }
        show(items);
    }

    /** Replaces the menu's items (null for a separator), resizing the menu if it is open. */
    private void show(List<JMenuItem> items) {
        menu.removeAll();
        for (JMenuItem item : items) {
            if (item == null) {
                menu.addSeparator();
            } else {
                menu.add(item);
            }
        }
        if (menu.isPopupMenuVisible()) {
            menu.getPopupMenu().pack();
            menu.getPopupMenu().revalidate();
            menu.getPopupMenu().repaint();
        }
    }

    private static JMenuItem disabled(String text) {
        JMenuItem item = new JMenuItem(text);
        item.setEnabled(false);
        return item;
    }

    private void switchTo(String id, String label, String current) {
        if (id.equals(current)) {
            return;
        }
        List<String> unrecoverable = switcher.streamingClients().stream()
                .filter(c -> c.currentApp() == null)
                .map(ClientView::hostname)
                .toList();
        if (!unrecoverable.isEmpty() && JOptionPane.showConfirmDialog(parent,
                "Switching restarts Sunshine. Orbit can't reconnect " + String.join(", ", unrecoverable)
                        + " afterwards because its stream wasn't started from Orbit. Switch to " + label + "?",
                "Switch monitor", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
                != JOptionPane.OK_OPTION) {
            return;
        }
        SunshineClient sunshine = host.sunshine();
        switching = true;
        // Streaming clients are disconnected for Sunshine's restart and reconnected afterwards.
        List<String> streaming = switcher.streamingClients().stream().map(ClientView::agentId).toList();
        owner.reconnecting(streaming);
        new SwingWorker<MonitorSwitcher.Result, String>() {
            @Override
            protected MonitorSwitcher.Result doInBackground() throws Exception {
                return switcher.switchTo(id, sunshine::selectMonitor, this::publish);
            }

            @Override
            protected void process(List<String> progress) {
                owner.status(progress.getLast());
            }

            @Override
            protected void done() {
                switching = false;
                owner.reconnected(streaming);
                MonitorSwitcher.Result result;
                try {
                    result = get();
                } catch (Exception e) {
                    owner.status("Switching to " + label + " failed.");
                    owner.error("Could not switch Sunshine to " + label, e);
                    return;
                }
                String reconnected = result.reconnected().isEmpty() ? ""
                        : " Reconnected " + String.join(", ", result.reconnected()) + ".";
                if (result.switchError() != null) {
                    owner.status("Switching to " + label + " failed." + reconnected);
                    JOptionPane.showMessageDialog(parent, result.switchError(),
                            "Could not switch Sunshine to " + label, JOptionPane.ERROR_MESSAGE);
                } else {
                    owner.status("Sunshine now streams " + label + "." + reconnected
                            + (result.displayNote() != null ? " " + result.displayNote() + "." : ""));
                }
                if (!result.failed().isEmpty()) {
                    JOptionPane.showMessageDialog(parent,
                            "Could not reconnect:\n" + String.join("\n", result.failed()),
                            "Reconnecting after monitor switch", JOptionPane.WARNING_MESSAGE);
                }
            }
        }.execute();
    }
}
