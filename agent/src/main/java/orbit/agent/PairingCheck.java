package orbit.agent;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Whether this client is paired with Sunshine, as {@code moonlight list} last said, and how sure that is: a check that
 * couldn't reach Sunshine, or that Moonlight didn't answer in time, is often a blip, so the last state is kept until
 * the next checks say the same. Used on the agent's worker thread only.
 */
final class PairingCheck {

    private static final Logger LOG = Logger.getLogger(PairingCheck.class.getName());
    /**
     * Pairing checks in a row that Moonlight didn't answer in time before the client shows it as a problem. One alone
     * is common right after a stream ends, and the next check, 30 s on, usually passes.
     */
    static final int NO_ANSWER_REPORTED_AFTER = 3;

    /** Asks Moonlight about pairing with a host; {@link MoonlightController#checkPairing} outside tests. */
    interface Checker {
        MoonlightController.Pairing check(String host) throws IOException, InterruptedException;
    }

    private final Checker checker;
    /** When the next periodic check is due after one (it's paced from the check's start). */
    private final Duration recheck;
    /** Null while unknown; see {@link #problem}. */
    private Boolean paired;
    /** Why pairing is unknown, in Moonlight's words where it gave some; else null. */
    private String problem;
    /** When the periodic check may run again (System.nanoTime). */
    private long nextCheck = System.nanoTime();
    /** Checks in a row that couldn't reach Sunshine; one alone isn't reported, as it's often a blip. */
    private int unreachableInARow;
    /** Checks in a row that Moonlight didn't answer in time; see {@link #NO_ANSWER_REPORTED_AFTER}. */
    private int noAnswerInARow;

    PairingCheck(Checker checker, Duration recheck) {
        this.checker = checker;
        this.recheck = recheck;
    }

    /** True paired, false not paired, null unknown (see {@link #problem}). */
    Boolean paired() {
        return paired;
    }

    /** Why pairing is unknown, or null. */
    String problem() {
        return problem;
    }

    /** Whether the periodic check is due. */
    boolean due() {
        return System.nanoTime() - nextCheck >= 0;
    }

    /** Sets the state from elsewhere, e.g. paired once pairing succeeded, or unknown while Moonlight is missing. */
    void set(Boolean now, String why) {
        if (!Objects.equals(now, paired) || !Objects.equals(why, problem)) {
            LOG.info(() -> "pairing: " + (now == null ? "unknown" : now ? "paired" : "not paired")
                    + (why != null ? " (" + why + ")" : ""));
        }
        paired = now;
        problem = why;
    }

    /** Checks pairing with {@code host} now, keeping the last state through a blip. */
    void refresh(String host) throws InterruptedException {
        try {
            long started = System.nanoTime();
            MoonlightController.Pairing pairing = checker.check(host);
            // Paced from the check's start: a 20 s timeout would otherwise skip the next tick.
            nextCheck = started + recheck.toNanos();
            unreachableInARow = pairing.kind() == MoonlightController.Pairing.Kind.UNREACHABLE
                    ? unreachableInARow + 1 : 0;
            noAnswerInARow = pairing.kind() == MoonlightController.Pairing.Kind.NO_ANSWER ? noAnswerInARow + 1 : 0;
            switch (pairing.kind()) {
                case PAIRED -> set(true, null);
                case NOT_PAIRED -> set(false, null);
                case UNREACHABLE -> {
                    String why = "Moonlight can't reach Sunshine on " + host + ": " + pairing.detail();
                    if (unreachableInARow == 1) {
                        // Often a blip that the next check, 30 s on, doesn't see: keep the last state until then.
                        LOG.info(() -> why + "; checking again before reporting it");
                    } else {
                        // Sunshine runs (the host would have said otherwise), so this is the network or a firewall.
                        set(null, why);
                    }
                }
                case NO_ANSWER -> {
                    String why = "Moonlight doesn't answer for Sunshine on " + host + ": " + pairing.detail();
                    if (noAnswerInARow < NO_ANSWER_REPORTED_AFTER) {
                        LOG.info(() -> why + "; checking again before reporting it");
                    } else {
                        set(null, why);
                    }
                }
                case FAILED -> set(null, pairing.detail());
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "cannot run Moonlight", e);
            set(null, "cannot run Moonlight: " + e.getMessage());
        }
    }

    /**
     * Checks pairing right before a stream: it may have been removed in Sunshine since the last check, and
     * moonlight-qt's stream would then show "pair first" and wait for someone to close it, printing nothing.
     *
     * @throws IllegalStateException saying why the stream can't start: not paired, or Sunshine can't be reached
     */
    void beforeStream(String host) throws IOException, InterruptedException {
        MoonlightController.Pairing pairing = checker.check(host);
        nextCheck = System.nanoTime() + recheck.toNanos();
        switch (pairing.kind()) {
            case PAIRED -> set(true, null);
            case NOT_PAIRED -> {
                set(false, null);
                throw new IllegalStateException("Sunshine no longer has this client paired; pair it again in Orbit");
            }
            case UNREACHABLE -> {
                String why = "Moonlight can't reach Sunshine on " + host + ": " + pairing.detail();
                set(null, why);
                throw new IllegalStateException(why);
            }
            // Not clearly unpaired: let the stream try, and report its own failure if it has one.
            case NO_ANSWER, FAILED -> LOG.warning(() -> "pairing check before streaming failed: " + pairing.detail());
        }
    }
}
