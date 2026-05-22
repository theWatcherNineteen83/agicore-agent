package de.agicore.agent.goal;

import java.util.Comparator;

/**
 * Resolves which goal should be pursued next when multiple goals compete.
 * <p>
 * The default resolver uses a simple utility formula:
 * <pre>score = priority × expectedReward / (resourceCost + 1)</pre>
 * <p>
 * Higher priority and reward increase score; higher cost decreases it.
 * Extension point: swap in a learned resolver in Phase 2.
 */
public class GoalConflictResolver {

    /**
     * Pick the highest-scoring active goal.
     *
     * @param goals all goals (active and inactive)
     * @return the winning goal, or {@code null} if none are active
     */
    public Goal resolve(java.util.Collection<Goal> goals) {
        return goals.stream()
                .filter(Goal::active)
                .max(Comparator.comparingDouble(this::score))
                .orElse(null);
    }

    /**
     * Compute a utility score for one goal.
     * <p>
     * Formula: {@code priority × expectedReward / (resourceCost + 1)}.
     * The +1 avoids division by zero. Cost acts as a penalty term.
     *
     * @param goal the goal to score
     * @return utility score (higher is better)
     */
    public double score(Goal goal) {
        return (goal.priority() * goal.expectedReward()) / (goal.resourceCost() + 1.0);
    }
}
