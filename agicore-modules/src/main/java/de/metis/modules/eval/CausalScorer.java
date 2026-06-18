package de.metis.modules.eval;

import de.metis.kernel.eval.*;
import de.metis.kernel.world.HypothesisStore;

import java.util.logging.Logger;

/**
 * Phase 10 — Scorer for causal inference capability.
 *
 * <p>Bewertet den HypothesisStore anhand von EvalTasks.
 * Die GroundTruth enthält Schwellwerte im Format "key >= value":
 * <ul>
 *   <li>{@code confirmed >= 3} — mind. 3 bestätigte Hypothesen</li>
 *   <li>{@code total >= 10} — mind. 10 Hypothesen insgesamt</li>
 *   <li>{@code open >= 0} — offene Hypothesen (nicht negativ)</li>
 * </ul>
 */
public class CausalScorer {

    private static final Logger LOG = Logger.getLogger(CausalScorer.class.getName());

    private final HypothesisStore hypothesisStore;

    public CausalScorer(HypothesisStore hypothesisStore) {
        this.hypothesisStore = hypothesisStore;
    }

    public MetricResult score(EvalTask task, MetisOutput output) {
        if (task.category() != Category.CAUSAL) {
            return new MetricResult(task.scoring().metric(), 0.0, task.scoring().gate());
        }

        String gt = extractGroundTruth(task.groundTruth());
        if (gt == null || gt.isBlank()) {
            return new MetricResult(task.scoring().metric(), 0.0, task.scoring().gate());
        }

        var parts = gt.split("\\s+");
        if (parts.length < 3) {
            LOG.warning("CausalScorer: invalid groundTruth '" + gt + "' — expected 'key >= value'");
            return new MetricResult(task.scoring().metric(), 0.0, task.scoring().gate());
        }

        String key = parts[0];
        String op = parts[1];
        double expected;
        try {
            expected = Double.parseDouble(parts[2]);
        } catch (NumberFormatException e) {
            LOG.warning("CausalScorer: invalid number in '" + gt + "'");
            return new MetricResult(task.scoring().metric(), 0.0, task.scoring().gate());
        }

        double actual = getValue(key);
        boolean passed = compare(actual, op, expected);
        double score = passed ? 1.0 : Math.min(1.0, actual / Math.max(0.001, expected));

        LOG.fine("CausalScorer: " + key + " " + op + " " + expected
                + " -> actual=" + String.format("%.1f", actual)
                + " score=" + String.format("%.2f", score));

        return new MetricResult(task.scoring().metric(), score, task.scoring().gate());
    }

    private static String extractGroundTruth(GroundTruth gt) {
        if (gt instanceof GroundTruth.ExactMatch em) {
            return em.expectedAnswer();
        }
        if (gt instanceof GroundTruth.JudgeRubric jr) {
            return jr.rubricJson();
        }
        if (gt instanceof GroundTruth.SimGoalState sgs) {
            return sgs.description();
        }
        // Fallback: toString()
        return gt.toString();
    }

    private double getValue(String key) {
        return switch (key.toLowerCase()) {
            case "confirmed" -> hypothesisStore.confirmedCount();
            case "refuted" -> hypothesisStore.refutedCount();
            case "total" -> hypothesisStore.size();
            case "open" -> hypothesisStore.open().size();
            default -> {
                LOG.warning("CausalScorer: unknown key '" + key + "'");
                yield 0.0;
            }
        };
    }

    private static boolean compare(double actual, String op, double expected) {
        return switch (op) {
            case ">=" -> actual >= expected;
            case ">" -> actual > expected;
            case "<=" -> actual <= expected;
            case "<" -> actual < expected;
            case "==", "=" -> Math.abs(actual - expected) < 0.001;
            default -> false;
        };
    }
}
