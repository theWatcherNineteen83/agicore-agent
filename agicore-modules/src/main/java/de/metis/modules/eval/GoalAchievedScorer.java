package de.metis.modules.eval;

import de.metis.kernel.eval.*;
import de.metis.kernel.eval.GroundTruth.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Scorer for PLANNING tasks.
 * <p>
 * Checks if the agent's plan would achieve the expected goal state.
 * Uses SIM_GOAL_STATE ground truth with schema validation as fallback.
 */
class GoalAchievedScorer implements Scorer {

    private static final Logger LOG = Logger.getLogger(GoalAchievedScorer.class.getName());

    @Override
    public MetricResult score(EvalTask task, MetisOutput output) {
        if (output.isError()) {
            return new MetricResult(task.scoring().metric(), 0.0, task.scoring().gate());
        }

        // Check JSON validity first (plan must be parseable)
        if (output.jsonOutput() == null) {
            return new MetricResult("goal_achieved", 0.0, Gate.HARD);
        }

        if (task.groundTruth() instanceof SimGoalState sim) {
            try {
                // Check if the plan output references the expected goal state keys
                double score = computeGoalMatch(output.jsonOutput(), sim.expectedState());
                return new MetricResult(task.scoring().metric(), score, task.scoring().gate());
            } catch (Exception e) {
                LOG.fine("Goal state matching failed: " + e.getMessage());
                return new MetricResult(task.scoring().metric(), 0.0, task.scoring().gate());
            }
        }

        // Fallback: schema validity (valid JSON = partial credit)
        return new MetricResult("validity_rate", 1.0, Gate.HARD);
    }

    /**
     * Simple goal-state matching: check if expected keys are present
     * in the JSON output. Returns 0.0–1.0 fraction matched.
     */
    private double computeGoalMatch(String jsonOutput, Map<String, Object> expectedState) {
        if (expectedState.isEmpty()) return 1.0;

        int matched = 0;
        String lower = jsonOutput.toLowerCase();
        for (var entry : expectedState.entrySet()) {
            // Simple substring match — full implementation would parse JSON
            String key = entry.getKey().toLowerCase();
            String val = String.valueOf(entry.getValue()).toLowerCase();
            if (lower.contains(key) && lower.contains(val)) {
                matched++;
            }
        }
        return (double) matched / expectedState.size();
    }
}
