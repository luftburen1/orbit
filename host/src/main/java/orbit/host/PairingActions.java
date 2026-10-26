package orbit.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.swing.JCheckBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import orbit.protocol.ClientState;
import orbit.protocol.Message;

/** Pairing, unpairing and removing clients, with their dialogs. All methods run on the Swing event thread. */
final class PairingActions {

    private static final String PAIRING = ClientActivities.PAIRING;
    private static final String UNPAIRING = ClientActivities.UNPAIRING;

    private final OrbitHost host;
    private final AgentRegistry registry;
    private final WindowSupport ui;
    /** Opens the settings at the Sunshine login. */
    private final Runnable editSunshine;

    PairingActions(OrbitHost host, WindowSupport ui, Runnable editSunshine) {
        this.host = host;
        this.registry = host.registry();
        this.ui = ui;
        this.editSunshine = editSunshine;
    }

    void pair(ClientView client) {
        if (client.pairingRequest() != null) {
            pairRequest(client);
            return;
        }
        if (host.sunshine() == null) {
            editSunshine.run();
            if (host.sunshine() == null) {
                return;
            }
        }
        String id = client.agentId();
        // "Pairing…" until the client answers, which is after the PIN is in and the pairing is checked.
        if (!ui.begin(id, PAIRING)) {
            return;
        }
        report(client, "Pairing", registry.pair(id), () -> {
            ui.activities.end(id, PAIRING);
            ui.refresh();
            refreshSunshinePairings();
        });
    }

    /**
     * Asks for the PIN a Moonlight client without an agent shows, and a name for it, then pairs its waiting request.
     * Its row gives way to the pairing's, which is then selected, so it reads as the same client, now paired.
     */
    void pairRequest(ClientView client) {
        String id = client.agentId();
        if (ui.activities.busy(id)) {
            return;
        }
        ui.focus(client);
        SunshineClient.PendingPairing request = client.pairingRequest();
        JTextField pin = new JTextField(6);
        JTextField name = new JTextField(client.hostname(), 18);
        JPanel form = Forms.form("PIN shown by Moonlight", pin, "Name in Sunshine", name);
        int answer = JOptionPane.showConfirmDialog(ui.frame, form, "Pair " + client.hostname(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        String pinText = pin.getText().strip();
        String nameText = name.getText().strip().isEmpty() ? client.hostname() : name.getText().strip();
        if (answer != JOptionPane.OK_OPTION || pinText.isEmpty() || !ui.begin(id, PAIRING)) {
            return;
        }
        ui.status("Pairing " + nameText + "…");
        new SwingWorker<Optional<SunshineClient.PairedClient>, Void>() {
            @Override
            protected Optional<SunshineClient.PairedClient> doInBackground() throws Exception {
                return host.pairInSunshine(request, pinText, nameText);
            }

            @Override
            protected void done() {
                ui.activities.end(id, PAIRING);
                try {
                    Optional<SunshineClient.PairedClient> paired = get();
                    ui.status("Paired " + nameText + " in Sunshine.");
                    ui.refresh();
                    paired.ifPresent(p -> ui.select(ClientView.AGENTLESS_PREFIX + p.uuid()));
                } catch (Exception e) {
                    ui.refresh();
                    ui.failed("Pairing " + nameText, e);
                }
            }
        }.execute();
    }

    /** Asks, then removes the client's pairing in Sunshine and ends its stream, if any. */
    void unpair(ClientView client) {
        String id = client.agentId();
        if (ui.activities.busy(id)) {
            return;
        }
        ui.focus(client);
        if (client.agentless()) {
            unpairAgentless(client);
            return;
        }
        String question = "Unpair " + client.hostname() + "? Sunshine forgets it, so it can't stream until it's paired"
                + " again" + (client.streaming() ? ", and its stream ends." : ".");
        int answer = JOptionPane.showConfirmDialog(ui.frame, question, "Unpair", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        if (!ui.begin(id, UNPAIRING)) {
            return;
        }
        ui.status("Unpairing " + client.hostname() + "…");
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                return unpairNow(client);
            }

            @Override
            protected void done() {
                try {
                    get();
                    ui.status("Unpaired " + client.hostname() + " in Sunshine. Pair it again to stream to it.");
                    // "Unpairing…" until the client reports it isn't paired; it checks right away when asked.
                    registry.checkPairing(id);
                    ui.clearLater(id, UNPAIRING, 15_000);
                } catch (Exception e) {
                    ui.activities.end(id, UNPAIRING);
                    ui.failed("Unpairing " + client.hostname(), e);
                }
                ui.refresh();
            }
        }.execute();
    }

    /**
     * Asks, then removes a pairing without an Orbit agent. Its row shows "Unpairing…" until Sunshine no longer lists
     * it. A stream it runs isn't ended: Orbit can't reach the client, and Sunshine doesn't end it itself.
     */
    private void unpairAgentless(ClientView client) {
        String id = client.agentId();
        int answer = JOptionPane.showConfirmDialog(ui.frame, "Unpair " + client.hostname() + "? Sunshine forgets this"
                + " Moonlight client, so it can't stream until it's paired again.", "Unpair",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION || !ui.begin(id, UNPAIRING)) {
            return;
        }
        ui.status("Unpairing " + client.hostname() + "…");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                host.unpairInSunshine(new SunshineClient.PairedClient(client.hostname(), client.pairingUuid()));
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    ui.status("Unpaired " + client.hostname() + " in Sunshine.");
                    ui.clearLater(id, UNPAIRING);
                } catch (Exception e) {
                    ui.activities.end(id, UNPAIRING);
                    ui.failed("Unpairing " + client.hostname(), e);
                }
                ui.refresh();
            }
        }.execute();
    }

    /** Asks, then disconnects the client's agent and forgets it, and unpairs it in Sunshine if ticked. */
    void remove(ClientView client) {
        ui.focus(client);
        List<Object> message = new ArrayList<>();
        message.add("Remove " + client.hostname() + "? Its agent will be disconnected and must be approved again.");
        // Ticked by default: a removed client usually shouldn't keep streaming from Sunshine on its own.
        JCheckBox unpair = new JCheckBox("Also unpair " + client.hostname() + " in Sunshine", true);
        if (client.pairedInSunshine() && host.sunshine() != null) {
            message.add(unpair);
        } else if (client.state() == ClientState.AVAILABLE || client.state() == ClientState.STREAMING) {
            message.add("It's paired in Sunshine under another name, listed as a client without an agent; unpair that"
                    + " too if you want.");
        }
        int answer = JOptionPane.showConfirmDialog(ui.frame, message.toArray(), "Remove client",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        if (!message.contains(unpair) || !unpair.isSelected()) {
            registry.remove(client.agentId());
            return;
        }
        ui.status("Unpairing " + client.hostname() + " and removing it…");
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                return unpairNow(client);
            }

            @Override
            protected void done() {
                try {
                    get();
                    ui.status("Removed " + client.hostname() + " and unpaired it in Sunshine.");
                } catch (Exception e) {
                    ui.status("Removed " + client.hostname() + ", but unpairing it in Sunshine failed.");
                    ui.error("Unpairing " + client.hostname() + " failed", e);
                }
                // Removed either way: that's what was asked, and the pairing can still go in Sunshine's web UI.
                registry.remove(client.agentId());
            }
        }.execute();
    }

    /** Removes the pairing in Sunshine and ends the client's stream; Sunshine doesn't end it itself. Blocking. */
    private int unpairNow(ClientView client) throws Exception {
        int removed = host.unpairInSunshine(client.hostname());
        if (client.streaming()) {
            registry.disconnectStream(client.agentId());
        }
        return removed;
    }

    /** Reads Sunshine's paired clients in the background, so Pair/Unpair follows a pairing at once. */
    private void refreshSunshinePairings() {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                host.refreshSunshinePairings();
                return null;
            }
        }.execute();
    }

    /**
     * Shows progress in the status bar and the outcome when the agent answers.
     *
     * @param done runs on the event thread when the command finishes, before any message is shown
     */
    private void report(ClientView client, String what, CompletableFuture<Message.CommandResult> result,
            Runnable done) {
        ui.status(what + " on " + client.hostname() + "…");
        result.whenComplete((r, err) -> SwingUtilities.invokeLater(() -> {
            done.run();
            if (err != null) {
                ui.failed(what + " on " + client.hostname(), err);
            } else if (!r.ok()) {
                ui.status(what + " on " + client.hostname() + " failed.");
                JOptionPane.showMessageDialog(ui.frame, r.error(), what + " failed", JOptionPane.ERROR_MESSAGE);
            } else {
                ui.status(what + " on " + client.hostname() + ": done.");
            }
        }));
    }
}
