package de.metis.kernel.world;

import java.util.logging.Logger;
import java.util.function.Supplier;

/**
 * Phase 10.3 — führt eine Hypothesen-Prüfung durch (do-Operator-Analog).
 *
 * <p>Schritt-Sequenz:
 * <ol>
 *   <li>Hypothese in TESTING setzen</li>
 *   <li>Pre-Measurement: {@code observable.get()}</li>
 *   <li>Caller löst Cause aus (Intervention) — passiert <em>außerhalb</em>
 *       dieser Klasse, damit der Kernel keinen Side-Effect erzeugt</li>
 *   <li>Caller ruft {@link #conclude(CausalHypothesis, double, double)}
 *       mit pre/post Wert</li>
 *   <li>Hypothese wird mit observedDirection/observedMagnitude/Status
 *       (CONFIRMED|REFUTED) versehen und gespeichert</li>
 *   <li>Optional: die korrespondierende {@link CausalModel#observe(...)}
 *       wird gefüttert</li>
 * </ol>
 *
 * <p>Diese Klasse ist bewusst klein: sie kapselt nur den Lifecycle und
 * die Übersetzung Pre/Post → Direction. Die eigentliche Intervention
 * bleibt Aufgabe der Module-Schicht.
 */
public class InterventionRunner {

    private static final Logger LOG = Logger.getLogger(InterventionRunner.class.getName());
    /** Below this absolute change we treat the effect as FLAT noise. */
    private static final double FLAT_THRESHOLD = 0.05;

    private final HypothesisStore store;
    private final CausalModel causal;

    public InterventionRunner(HypothesisStore store, CausalModel causal) {
        this.store = store;
        this.causal = causal;
    }

    public CausalHypothesis startTesting(CausalHypothesis h) {
        if (h == null) return null;
        return store.upsert(h.withStatus(CausalHypothesis.Status.TESTING));
    }

    /**
     * Conclude the intervention with a pre/post observation.
     *
     * @param h    the hypothesis under test
     * @param pre  measurement before the intervention
     * @param post measurement after the intervention
     * @return the updated hypothesis (CONFIRMED or REFUTED)
     */
    public CausalHypothesis conclude(CausalHypothesis h, double pre, double post) {
        if (h == null) return null;
        double delta = post - pre;
        double mag = Math.min(1.0, Math.abs(delta) / Math.max(1e-6, Math.abs(pre) + 1.0));
        CausalHypothesis.Direction observed;
        if (Math.abs(delta) < FLAT_THRESHOLD) observed = CausalHypothesis.Direction.FLAT;
        else if (delta > 0) observed = CausalHypothesis.Direction.UP;
        else observed = CausalHypothesis.Direction.DOWN;

        String note = String.format(java.util.Locale.ROOT,
                "pre=%.3f post=%.3f Δ=%.3f", pre, post, delta);
        CausalHypothesis updated = store.upsert(h.withResult(observed, mag, note));

        // Feed CausalModel with the observation so future predictions improve
        if (causal != null) {
            boolean success = updated.status() == CausalHypothesis.Status.CONFIRMED;
            causal.observe(updated.cause(), updated.condition(), updated.effect(), success);
        }
        LOG.info("InterventionRunner: " + updated.status() + " — " + updated.cause()
                + " → " + updated.effect() + " (" + note + ")");
        return updated;
    }

    /** Convenience: run a synchronous intervention with measurement supplier. */
    public CausalHypothesis runSync(CausalHypothesis h, Supplier<Double> measure, Runnable intervention) {
        CausalHypothesis test = startTesting(h);
        double pre = measure.get();
        try {
            intervention.run();
        } catch (Exception e) {
            LOG.warning("InterventionRunner: intervention threw " + e.getMessage());
        }
        double post = measure.get();
        return conclude(test, pre, post);
    }
}
