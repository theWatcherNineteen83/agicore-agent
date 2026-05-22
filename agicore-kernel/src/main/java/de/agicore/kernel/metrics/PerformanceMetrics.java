package de.agicore.kernel.metrics;

import de.agicore.kernel.action.ActionResult;
import de.agicore.kernel.goal.Goal;
import de.agicore.kernel.meta.MetaCognition;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Tracks agent performance across all cognitive cycles.
 * <p>
 * Three core dimensions:
 * <ol>
 *   <li><b>Goal success rate</b> — fraction of completed goals that succeeded</li>
 *   <li><b>Planning efficiency</b> — fraction of planning attempts that yielded
 *       a non-empty plan</li>
 *   <li><b>Resource usage</b> — cumulative resource cost of all attempted goals</li>
 * </ol>
 * <p>
 * Snapshots are stored in a ring buffer for trend analysis. This data feeds
 * the adaptive reprioritization and hyperparameter mutation in Phase 2.
 */
public class PerformanceMetrics {

    private static final Logger LOG = Logger.getLogger(PerformanceMetrics.class.getName());
    private static final int MAX_SNAPSHOTS = 1_000;

    private long totalTicks = 0;
    private long successfulGoals = 0;
    private long failedGoals = 0;
    private long plansAttempted = 0;
    private long plansSuccessful = 0;
    private long totalResourceCost = 0;

    private final List<MetricSnapshot> history = new ArrayList<>(MAX_SNAPSHOTS);

    /**
     * Record a tick's outcome. Called by AgentCoreLoop after each cycle.
     *
     * @param goal   the goal that was pursued (may be null for idle tick)
     * @param result the action result (may be null if no plan was produced)
     * @param meta   current metacognitive state
     */
    public void recordTick(Goal goal, ActionResult result, MetaCognition meta) {
        totalTicks++;
        plansAttempted++;

        if (result != null) {
            plansSuccessful++;
            totalResourceCost += goal != null ? goal.resourceCost() : 0;

            if (result.success()) {
                successfulGoals++;
            } else {
                failedGoals++;
            }
        }
        // else: plan was empty → planning failure (no action)

        MetricSnapshot snap = new MetricSnapshot(
                Instant.now(),
                totalTicks,
                goalSuccessRate(),
                planningEfficiency(),
                totalResourceCost,
                meta.rollingError(),
                meta.confidence(),
                -1, // activeGoals not tracked here (caller provides via GoalManager)
                (int) (successfulGoals + failedGoals)
        );
        history.add(snap);

        if (history.size() > MAX_SNAPSHOTS) {
            history.removeFirst();
        }

        LOG.fine(() -> "Metrics: " + snap);
    }

    /** Fraction of completed goals that succeeded. 1.0 if no goals completed yet. */
    public double goalSuccessRate() {
        long total = successfulGoals + failedGoals;
        return total == 0 ? 1.0 : (double) successfulGoals / total;
    }

    /** Fraction of planning attempts that produced an action. */
    public double planningEfficiency() {
        return plansAttempted == 0 ? 1.0 : (double) plansSuccessful / plansAttempted;
    }

    /** Cumulative resource units consumed. */
    public long resourceUsage() {
        return totalResourceCost;
    }

    /** Average resource cost per successful goal. */
    public double averageCostPerSuccess() {
        return successfulGoals == 0 ? 0.0 : (double) totalResourceCost / successfulGoals;
    }

    /**
     * Trend of goal success rate over the last {@code window} snapshots.
     * Positive = improving, negative = degrading.
     */
    public double successRateTrend(int window) {
        if (history.size() < 2) return 0.0;
        int w = Math.min(window, history.size());
        var recent = history.subList(history.size() - w, history.size());
        double first = recent.getFirst().goalSuccessRate();
        double last = recent.getLast().goalSuccessRate();
        return last - first;
    }

    /**
     * Whether the agent's performance is improving.
     * Criteria: last 50 ticks show positive success rate trend AND
     * planning efficiency is above 50%.
     */
    public boolean isImproving() {
        return successRateTrend(50) > 0.0 && planningEfficiency() > 0.5;
    }

    /** Recent snapshots for analysis (most recent last). */
    public List<MetricSnapshot> recentHistory(int n) {
        int from = Math.max(0, history.size() - n);
        return List.copyOf(history.subList(from, history.size()));
    }

    // ── Huyen Kap. 6: Error Propagation Tracking ──────────

    /**
     * Estimated step-level success probability.
     * Huyen: "95% per step → 60% after 10 steps."
     * We compute: stepLevelSuccess = goalSuccessRate ^ (1 / avgStepsPerGoal)
     * This estimates how reliable each individual action is.
     */
    public double stepLevelSuccessRate(double avgStepsPerGoal) {
        if (avgStepsPerGoal <= 0 || goalSuccessRate() <= 0) return goalSuccessRate();
        return Math.pow(goalSuccessRate(), 1.0 / Math.max(1.0, avgStepsPerGoal));
    }

    /**
     * Predicted success rate for a plan of N steps.
     * Formula: stepLevelSuccess^N (Huyen's error propagation model).
     */
    public double predictedPlanSuccess(int steps, double avgStepsPerGoal) {
        double stepRate = stepLevelSuccessRate(avgStepsPerGoal);
        return Math.pow(stepRate, steps);
    }

    /**
     * Whether the plan length would degrade success below the given threshold.
     * If so, the plan should be shortened or validated more carefully.
     */
    public boolean planTooRisky(int steps, double avgStepsPerGoal, double minSuccess) {
        return predictedPlanSuccess(steps, avgStepsPerGoal) < minSuccess;
    }

    public long totalTicks() { return totalTicks; }
    public long successfulGoalsCount() { return successfulGoals; }
    public long failedGoalsCount() { return failedGoals; }

    @Override
    public String toString() {
        return String.format("Metrics[ticks=%d succ=%.2f plan=%.2f res=%d]",
                totalTicks, goalSuccessRate(), planningEfficiency(), totalResourceCost);
    }
}
