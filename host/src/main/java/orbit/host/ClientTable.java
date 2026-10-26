package orbit.host;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javax.swing.Icon;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import orbit.protocol.StreamSettings;

/**
 * The window's client list: one row per client with its status (an icon before some, pulsing while streaming,
 * starting or stopping), the host-monitor icon, app, settings and when it was last seen, then Moonlight clients
 * without an agent: waiting to pair, and greyed out, paired in Sunshine.
 * Event thread only.
 */
final class ClientTable {

    private final Model model;
    private final JTable table;

    /** @param activity what Orbit is doing to a client, e.g. "Stopping…", or null; shown instead of its status */
    ClientTable(Function<String, String> activity) {
        model = new Model(activity);
        table = new JTable(model) {
            /** Hovering a client shows its state and versions; hovering the lead's crown explains it. */
            @Override
            public String getToolTipText(MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                if (row < 0) {
                    return null;
                }
                ClientView client = model.get(convertRowIndexToModel(row));
                if (convertColumnIndexToModel(columnAtPoint(e.getPoint())) == Model.LEAD
                        && client.agentId().equals(model.monitorOwner)) {
                    return "Leads the stream: the host monitor follows this client's resolution, and other clients"
                            + " can only join its app";
                }
                return tooltip(client);
            }

            /** Pairings without an agent are greyed out: Orbit can't control them. Waiting requests aren't. */
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                // The renderers are shared by all rows and keep a foreground set on them, so clear the grey first.
                if (renderer instanceof DefaultTableCellRenderer cells) {
                    cells.setForeground(null);
                }
                Component c = super.prepareRenderer(renderer, row, column);
                if (!isRowSelected(row) && model.get(convertRowIndexToModel(row)).pairingUuid() != null) {
                    c.setForeground(UIManager.getColor("Label.disabledForeground"));
                }
                return c;
            }
        };
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setRowHeight(28);
        table.getColumnModel().getColumn(Model.CLIENT).setCellRenderer(new ClientRenderer());
        table.getColumnModel().getColumn(Model.STATUS).setCellRenderer(new StatusRenderer());
    }

    JTable component() {
        return table;
    }

    /**
     * Shows {@code clients}, keeping the selection.
     *
     * @param monitorOwner the streaming client the host monitor follows, or null
     */
    void setClients(List<ClientView> clients, String monitorOwner) {
        String selectedId = selected().map(ClientView::agentId).orElse(null);
        model.clients = new ArrayList<>(clients);
        model.monitorOwner = monitorOwner;
        model.fireTableDataChanged();
        if (selectedId != null) {
            select(selectedId);
        }
    }

    /** The streaming client the host monitor follows, or null. */
    String monitorOwner() {
        return model.monitorOwner;
    }

    Optional<ClientView> selected() {
        int row = table.getSelectedRow();
        return row < 0 ? Optional.empty() : Optional.of(model.get(table.convertRowIndexToModel(row)));
    }

    void select(String agentId) {
        for (int row = 0; row < table.getRowCount(); row++) {
            if (model.get(table.convertRowIndexToModel(row)).agentId().equals(agentId)) {
                table.setRowSelectionInterval(row, row);
            }
        }
    }

    /** Whether a row's status has a pulsing icon: "Streaming", "Starting…" or "Stopping…". */
    boolean anyPulsing() {
        for (int row = 0; row < model.getRowCount(); row++) {
            if (StatusRenderer.pulses(model.getValueAt(row, Model.STATUS))) {
                return true;
            }
        }
        return false;
    }

    /** Paints the status cells with pulsing icons now, for their next frame. */
    void paintPulsingIcons() {
        for (int row = 0; row < model.getRowCount(); row++) {
            if (StatusRenderer.pulses(model.getValueAt(row, Model.STATUS))) {
                table.paintImmediately(table.getCellRect(row, Model.STATUS, false));
            }
        }
    }

    /** Rows the window has room for at its smallest. */
    private static final int COMPACT_ROWS = 2;

    /**
     * Sizes the table to its content, so the window opens as small as it can: each column as wide as its header, its
     * widest cell and a typical long value, and as tall as the clients, at least two rows.
     */
    void fitToContent() {
        String[] typical = {"", "Awaiting approval", "", "Desktop", settingsText(StreamSettings.DEFAULT),
                "59 min ago"};
        FontMetrics metrics = table.getFontMetrics(table.getFont());
        int total = 0;
        for (int col = 0; col < table.getColumnCount(); col++) {
            TableColumn column = table.getColumnModel().getColumn(col);
            int width = table.getTableHeader().getDefaultRenderer()
                    .getTableCellRendererComponent(table, column.getHeaderValue(), false, false, -1, col)
                    .getPreferredSize().width;
            for (int row = 0; row < table.getRowCount(); row++) {
                width = Math.max(width,
                        table.prepareRenderer(table.getCellRenderer(row, col), row, col).getPreferredSize().width);
            }
            width = Math.max(width, metrics.stringWidth(typical[col])) + 16;
            column.setPreferredWidth(width);
            total += width;
        }
        table.setPreferredScrollableViewportSize(
                new Dimension(total, table.getRowHeight() * Math.max(COMPACT_ROWS, table.getRowCount())));
    }

    /** How much taller {@link #fitToContent} made the table than its compact view, which shows two rows. */
    int heightBeyondCompact() {
        return table.getRowHeight() * Math.max(0, table.getRowCount() - COMPACT_ROWS);
    }

    private static String tooltip(ClientView c) {
        StringBuilder html = new StringBuilder("<html><b>").append(escape(c.hostname())).append("</b>");
        c.stateLines().forEach(line -> html.append("<br>").append(escape(line)));
        c.softwareLines().forEach(line -> html.append("<br>").append(escape(line)));
        return html.append("</html>").toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** E.g. "1920x1080 @ 60 fps, 20.0 Mbps, HEVC, V-Sync off"; what's left to Moonlight isn't listed. */
    static String settingsText(StreamSettings s) {
        List<String> parts = new ArrayList<>(List.of(s.resolution() + " @ " + s.fps() + " fps",
                s.bitrateKbps() / 1000.0 + " Mbps"));
        if (s.videoCodec() != null) {
            parts.add(s.videoCodec());
        }
        onOff(parts, "V-Sync", s.vsync());
        onOff(parts, "frame pacing", s.framePacing());
        if (s.audioConfig() != null) {
            parts.add(s.audioConfig().replace('-', ' '));
        }
        onOff(parts, "overlay", s.performanceOverlay());
        return String.join(", ", parts);
    }

    private static void onOff(List<String> parts, String name, Boolean on) {
        if (on != null) {
            parts.add(name + (on ? " on" : " off"));
        }
    }

    private static String ago(Instant when) {
        if (when == null) {
            return "never";
        }
        Duration d = Duration.between(when, Instant.now());
        if (d.toMinutes() < 1) {
            return "just now";
        }
        if (d.toHours() < 1) {
            return d.toMinutes() + " min ago";
        }
        if (d.toDays() < 1) {
            return d.toHours() + " h ago";
        }
        return d.toDays() + " d ago";
    }

    /** The client column: the name, with a TV remote after it for clients that read their TV's remote. */
    private static final class ClientRenderer extends DefaultTableCellRenderer {
        private static final Icon REMOTE = Icons.remote(16);

        ClientRenderer() {
            setHorizontalTextPosition(LEADING);
            setIconTextGap(6);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            ClientView client = ((Model) table.getModel()).get(table.convertRowIndexToModel(row));
            setIcon(client.remote() ? REMOTE : null);
            return this;
        }
    }

    /**
     * The status column, with a pulsing green dot before "Streaming", a green ring before "Available", so clients
     * ready to stream stand out, and the Start and Stop buttons' icons before "Starting…" and "Stopping…", pulsing
     * like the button while its action runs. Faults, e.g. "Sunshine not running", are red.
     */
    private static final class StatusRenderer extends DefaultTableCellRenderer {
        static final String STREAMING = "Streaming";
        static final String AVAILABLE = "Available";
        private static final Icon DOT = Icons.pulsingDot(12);
        private static final Icon RING = Icons.readyRing(12);
        private static final Icon STARTING = Icons.pulsing(Icons.play(12));
        private static final Icon STOPPING = Icons.pulsing(Icons.stop(12));

        static boolean pulses(Object status) {
            return STREAMING.equals(status) || ClientActivities.STARTING.equals(status)
                    || ClientActivities.STOPPING.equals(status);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            ClientView client = ((Model) table.getModel()).get(table.convertRowIndexToModel(row));
            if (!selected && client.statusIsFault() && client.statusLabel().equals(value)) {
                setForeground(UIManager.getColor("Actions.Red"));
            }
            setIcon(value instanceof String status ? switch (status) {
                case STREAMING -> DOT;
                case AVAILABLE -> RING;
                case ClientActivities.STARTING -> STARTING;
                case ClientActivities.STOPPING -> STOPPING;
                default -> null;
            } : null);
            return this;
        }
    }

    private static final class Model extends AbstractTableModel {
        private static final String[] COLUMNS = {"Client", "Status", "Lead", "App", "Settings", "Last seen"};
        static final int CLIENT = 0;
        static final int STATUS = 1;
        static final int LEAD = 2;
        private static final Icon LEAD_ICON = Icons.crown(16);

        private final Function<String, String> activity;
        private List<ClientView> clients = new ArrayList<>();
        /** The streaming client the host monitor follows, or null. */
        private String monitorOwner;

        Model(Function<String, String> activity) {
            this.activity = activity;
        }

        ClientView get(int row) {
            return clients.get(row);
        }

        @Override
        public int getRowCount() {
            return clients.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        /** The lead column is an icon, which JTable's default renderer centres. */
        @Override
        public Class<?> getColumnClass(int column) {
            return column == LEAD ? Icon.class : Object.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            ClientView c = clients.get(row);
            return switch (column) {
                case CLIENT -> c.hostname();
                case STATUS -> Objects.requireNonNullElse(activity.apply(c.agentId()), c.statusLabel());
                case LEAD -> c.agentId().equals(monitorOwner) ? LEAD_ICON : null;
                case 3 -> c.currentApp() != null ? c.currentApp() : "";
                case 4 -> c.agentless() ? "" : settingsText(c.settings());
                case 5 -> c.agentless() ? "" : c.online() ? "now" : ago(c.lastSeen());
                default -> "";
            };
        }
    }
}
