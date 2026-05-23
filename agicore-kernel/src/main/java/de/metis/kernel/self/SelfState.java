package de.metis.kernel.self;

import java.time.Instant;

/**
 * A point-in-time snapshot of the agent's internal state.
 * <p>
 * This is what the agent "knows about itself" — its capabilities,
 * current load, emotional-analogue state (confidence, surprise).
 * The SelfModel uses a history of these snapshots to build a
 * running model of its own performance envelope.
 *
 * @param timestamp         when this snapshot was taken
 * @param confidence        metacognitive confidence (0.0–1.0)
 * @param rollingError      EMA prediction error
 * @param isSurprised       whether the agent is in a surprised state
 * @param activeGoals       number of goals currently pursued
 * @param completedGoals    cumulative completed goals
 * @param goalSuccessRate   fraction of goals succeeded
 * @param planningEfficiency fraction of plans that produced actions
 * @param resourceUsage     cumulative resource cost
 * @param stmSize           current short-term memory occupancy
 * @param ltmSize           current long-term memory size
 * @param attentionFocus    what the agent is currently attending to (summary)
 */
public record SelfState(
        Instant timestamp,
        double confidence,
        double rollingError,
        boolean isSurprised,
        int activeGoals,
        int completedGoals,
        double goalSuccessRate,
        double planningEfficiency,
        long resourceUsage,
        int stmSize,
        int ltmSize,
        String attentionFocus) {

    /**
     * How "healthy" the agent feels about its own state.
     * Composite of confidence (50%), success rate (30%), and inverse load (20%).
     * Ranges 0.0 (critical) to 1.0 (optimal).
     */
    public double selfHealth() {
        double loadFactor = activeGoals > 0 ? Math.min(1.0, 5.0 / activeGoals) : 1.0;
        return 0.5 * confidence + 0.3 * goalSuccessRate + 0.2 * loadFactor;
    }

    /**
     * Whether the agent considers itself "stressed" —
     * high error, many active goals, low success.
     */
    public boolean isStressed() {
        return rollingError > 0.5 && activeGoals > 3;
    }

    @Override
    public String toString() {
        return String.format("SelfState[conf=%.2f err=%.2f %s goals=%d/%d succ=%.0f%% health=%.0f%%]",
                confidence, rollingError,
                isSurprised ? "surprised" : "stable",
                activeGoals, completedGoals,
                goalSuccessRate * 100, selfHealth() * 100);
    }
}
