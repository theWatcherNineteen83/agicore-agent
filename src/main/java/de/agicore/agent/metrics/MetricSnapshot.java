package de.agicore.agent.metrics;

import java.time.Instant;

/**
 * Immutable point-in-time snapshot of agent performance.
 * <p>
 * Captured at the end of each cognitive cycle tick so trends
 * can be analysed without locking the live metrics tracker.
 *
 * @param timestamp           wall-clock time of snapshot
 * @param tickNumber          which tick this was
 * @param goalSuccessRate     fraction of completed goals that succeeded (0.0–1.0)
 * @param planningEfficiency  fraction of plans that produced a valid action (0.0–1.0)
 * @param resourceUsage       cumulative abstract resource units consumed
 * @param averagePredictionError  EMA of prediction error at this point
 * @param confidence          metacognitive confidence at this point
 * @param activeGoals         how many goals are still active
 * @param completedGoals      how many goals have been finished
 */
public record MetricSnapshot(
        Instant timestamp,
        long tickNumber,
        double goalSuccessRate,
        double planningEfficiency,
        long resourceUsage,
        double averagePredictionError,
        double confidence,
        int activeGoals,
        int completedGoals) {

    @Override
    public String toString() {
        return String.format(
                "T%04d | succ=%.2f plan=%.2f res=%d err=%.3f conf=%.3f act=%d done=%d",
                tickNumber, goalSuccessRate, planningEfficiency,
                resourceUsage, averagePredictionError, confidence,
                activeGoals, completedGoals);
    }
}
