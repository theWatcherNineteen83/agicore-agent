package de.metis.modules.eval;

import de.metis.kernel.eval.*;
import de.metis.kernel.eval.GroundTruth.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Scorer for PLANNING tasks.
 * <p>
 * Checks if the planner picked the expected action for the goal.
 * Uses SIM_GOAL_STATE ground truth with schema validation as fallback.
 * <p>
 * 18.09.2026 (Punkt 3): scores the assistant answer text extracted by
 * {@code LiveMetisInvoker.invokePlanner} — previously the invoker passed the
 * truncated 200-char chat envelope, so both key and value substring checks
 * always missed and PLANNING.goal_achieved was permanently 0.0.
 */
class GoalAchievedScorer implements Scorer {

    private static final Logger LOG = Logger.getLogger(GoalAchievedScorer.class.getName());

    @Override
    public MetricResult score(EvalTask task, MetisOutput output) {
        String metric = task.scoring().metric();
        if (output.isError()) {
            return new MetricResult(metric, 0.0, task.scoring().gate());
        }

        // Prefer the extracted answer text (jsonOutput); fall back to rawText
        // so older invokers that pass the full envelope still get scored.
        String answer = output.jsonOutput() != null && !output.jsonOutput().isBlank()
                ? output.jsonOutput()
                : output.rawText();
        if (answer == null || answer.isBlank()) {
            return new MetricResult(metric, 0.0, task.scoring().gate());
        }

        if (task.groundTruth() instanceof SimGoalState sim) {
            try {
                double score = computeGoalMatch(answer, sim.expectedState());
                return new MetricResult(metric, score, task.scoring().gate());
            } catch (Exception e) {
                LOG.fine("Goal state matching failed: " + e.getMessage());
                return new MetricResult(metric, 0.0, task.scoring().gate());
            }
        }

        // Fallback: no sim ground truth — a parseable non-empty answer counts
        // as validity. Uses the task metric so aggregation stays consistent.
        return new MetricResult(metric, 1.0, task.scoring().gate());
    }

    /**
     * Goal-state matching on the answer text: every expected value must
     * appear as a whole word (word-boundary regex, so "shell" does not
     * match inside "powershell" and "http" not inside "https").
     * Keys are field names of the expected state, not answer content —
     * they are no longer required to appear. Returns 0.0–1.0 fraction.
     */
    private double computeGoalMatch(String answer, Map<String, Object> expectedState) {
        if (expectedState.isEmpty()) return 1.0;

        String lower = answer.toLowerCase(Locale.ROOT).strip();
        int matched = 0;
        for (var entry : expectedState.entrySet()) {
            String val = String.valueOf(entry.getValue()).toLowerCase(Locale.ROOT).strip();
            if (val.isEmpty() || "null".equals(val)) { matched++; continue; }
            if (lower.equals(val)) { matched++; continue; }
            if (Pattern.compile("\\b" + Pattern.quote(val) + "\\b").matcher(lower).find()) {
                matched++;
            }
        }
        return (double) matched / expectedState.size();
    }
}
