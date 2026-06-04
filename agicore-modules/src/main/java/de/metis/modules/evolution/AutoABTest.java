package de.metis.modules.evolution;

import de.metis.kernel.world.CausalHypothesis;
import de.metis.kernel.world.HypothesisGenerator;
import de.metis.kernel.world.HypothesisStore;
import de.metis.kernel.world.InterventionRunner;

import java.util.*;
import java.util.logging.Logger;

/**
 * Phase 12c — Auto-ABTest: Frommetrie-Patterns zu testbaren Hypothesen.
 *
 * <p>Nimmt PatternDetector-Ergebnisse, generiert kausale Hypothesen
 * und testet sie via InterventionRunner.
 */
public class AutoABTest {

    private static final Logger LOG = Logger.getLogger(AutoABTest.class.getName());

    private final HypothesisGenerator generator;
    private final HypothesisStore store;
    private final InterventionRunner runner;

    private int testsRun = 0;
    private int testsConfirmed = 0;
    private final Set<String> recentPatternIds = new HashSet<>();

    public AutoABTest(HypothesisGenerator generator, HypothesisStore store,
                      InterventionRunner runner) {
        this.generator = generator;
        this.store = store;
        this.runner = runner;
    }

    /**
     * Nimmt PatternDetector-Ergebnisse, generiert und testet Hypothesen.
     */
    public void process(List<PatternDetector.Pattern> patterns) {
        for (var p : patterns) {
            if (recentPatternIds.contains(p.id())) continue;
            recentPatternIds.add(p.id());

            // Generate causal hypothesis from pattern
            String cause = p.id();
            String condition = "auto-detected at " + System.currentTimeMillis();
            String effect = p.description().length() > 100
                    ? p.description().substring(0, 100)
                    : p.description();
            String rationale = "Auto-ABTest: pattern '" + p.id()
                    + "' (prio=" + p.priority() + ")";

            var h = generator.propose(cause, condition, effect, rationale);
            var saved = store.upsert(h);

            if (runner != null && saved != null) {
                try {
                    double pre = store.open().size();
                    Runnable noop = () -> {};
                    var tested = runner.runSync(saved, () -> pre, noop);
                    testsRun++;
                    if (tested != null && tested.status() == CausalHypothesis.Status.CONFIRMED) {
                        testsConfirmed++;
                    }
                    LOG.info("AutoABTest #" + testsRun + ": " + p.id()
                            + " -> " + (tested != null ? tested.status() : "FAILED"));
                } catch (Exception e) {
                    LOG.fine("AutoABTest: intervention failed: " + e.getMessage());
                }
            }
        }
    }

    public int testsRun() { return testsRun; }
    public int testsConfirmed() { return testsConfirmed; }
}
