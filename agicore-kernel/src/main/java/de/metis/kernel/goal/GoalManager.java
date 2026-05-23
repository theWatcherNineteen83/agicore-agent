package de.metis.kernel.goal;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Central registry for goals the agent is tracking.
 * <p>
 * Goals are stored by id. The manager supports add, remove, complete,
 * and reprioritise operations. Thread-safe via {@link ConcurrentHashMap}.
 * <p>
 * Conflict resolution is delegated to {@link GoalConflictResolver} so
 * the resolution strategy can be swapped without touching this class.
 */
public class GoalManager {

    private static final Logger LOG = Logger.getLogger(GoalManager.class.getName());

    private final Map<UUID, Goal> goals = new ConcurrentHashMap<>();
    private final GoalConflictResolver resolver;

    /**
     * Tracks success/failure counts per goal category for adaptive reprioritization.
     * Key = simplified category string (lowercased first two words of goal description).
     */
    private final Map<String, CategoryStats> categoryStats = new ConcurrentHashMap<>();

    public GoalManager() {
        this(new GoalConflictResolver());
    }

    public GoalManager(GoalConflictResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Register a new active goal.
     *
     * @return the stored goal
     */
    public Goal add(Goal goal) {
        goals.put(goal.id(), goal);
        LOG.info(() -> "Goal added: " + goal);
        return goal;
    }

    /**
     * Convenience: create and add a goal in one call.
     */
    public Goal add(String description, int priority, double expectedReward, int resourceCost) {
        return add(new Goal(description, priority, expectedReward, resourceCost));
    }

    /**
     * Remove a goal entirely (even if completed).
     */
    public void remove(UUID id) {
        Goal removed = goals.remove(id);
        if (removed != null) {
            LOG.info(() -> "Goal removed: " + removed);
        }
    }

    /**
     * Mark a goal as completed (deactivated but retained).
     *
     * @return the updated goal, or {@code null} if not found
     */
    public Goal complete(UUID id) {
        Goal old = goals.get(id);
        if (old == null) return null;
        Goal done = old.deactivate();
        goals.put(id, done);
        LOG.info(() -> "Goal completed: " + done);
        return done;
    }

    /**
     * Change the priority of an active goal.
     */
    public Goal reprioritise(UUID id, int newPriority) {
        Goal old = goals.get(id);
        if (old == null) return null;
        Goal updated = old.withPriority(newPriority);
        goals.put(id, updated);
        LOG.fine(() -> "Reprioritised: " + updated);
        return updated;
    }

    /**
     * Determine which active goal should be pursued next.
     */
    public Goal nextGoal() {
        return resolver.resolve(goals.values());
    }

    /**
     * Architecture Hardening: Workspace-biased goal selection.
     * <p>
     * Active goals matching the attention focus get a temporary
     * priority boost for this selection only. This makes the
     * Global Workspace causally influence goal selection — the
     * agent pursues what it's attending to.
     *
     * @param attentionKeywords keywords from attention broadcast
     * @param boostAmount       temporary priority increase (e.g. 20)
     * @return the winning goal (biased by attention)
     */
    public Goal nextGoalBiased(List<String> attentionKeywords, int boostAmount) {
        if (attentionKeywords.isEmpty()) {
            return nextGoal();
        }

        // Create temporary boosted copies for scoring only
        List<Goal> boosted = goals.values().stream()
                .filter(Goal::active)
                .map(g -> {
                    for (String kw : attentionKeywords) {
                        String lowerKw = kw.toLowerCase();
                        if (g.description().toLowerCase().contains(lowerKw)
                                || g.category().toLowerCase().contains(lowerKw)) {
                            return g.withPriority(Math.min(100, g.priority() + boostAmount));
                        }
                    }
                    return g;
                })
                .toList();

        Goal winner = resolver.resolve(boosted);
        if (winner != null) {
            LOG.fine(() -> "Workspace-biased selection: " + winner.description()
                    + " (boost=" + boostAmount + ", keywords=" + attentionKeywords + ")");
        }
        return winner;
    }

    /** All goals (active and inactive). Unmodifiable snapshot. */
    public Collection<Goal> all() {
        return List.copyOf(goals.values());
    }

    /** Only active goals, sorted by resolver score (descending). */
    public List<Goal> activeByScore() {
        return goals.values().stream()
                .filter(Goal::active)
                .sorted((a, b) -> Double.compare(resolver.score(b), resolver.score(a)))
                .toList();
    }

    public int activeCount() {
        return (int) goals.values().stream().filter(Goal::active).count();
    }

    // ── Phase 2: Adaptive Reprioritization ──────────────────────

    /**
     * Record the outcome of a goal pursuit so similar future goals
     * can be reprioritised.
     *
     * @param goal    the goal that was attempted
     * @param success whether the action succeeded
     */
    public void recordOutcome(Goal goal, boolean success) {
        String category = categorise(goal);
        categoryStats.computeIfAbsent(category, k -> new CategoryStats()).record(success);
        LOG.fine(() -> "Category '" + category + "': " + categoryStats.get(category));
    }

    /**
     * Adapt priorities of all active goals based on historical success rates
     * of their categories.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>For each active goal, look up its category's success rate</li>
     *   <li>If success rate &lt; 0.3: deprioritise by 20% (learning from failure)</li>
     *   <li>If success rate &gt; 0.7: boost priority by 10% (reinforce success)</li>
     *   <li>If category has &lt; 3 samples: no adjustment (insufficient data)</li>
     * </ol>
     * <p>
     * This is the memory-based policy adjustment mechanism — the agent
     * learns which goal categories yield success and shifts focus accordingly.
     *
     * @return number of goals whose priority was changed
     */
    public int adaptPriorities() {
        int changed = 0;
        for (Goal goal : goals.values()) {
            if (!goal.active()) continue;

            String category = categorise(goal);
            CategoryStats stats = categoryStats.get(category);
            if (stats == null || stats.total() < 3) continue;

            double successRate = stats.successRate();
            int oldPriority = goal.priority();
            int newPriority;

            if (successRate < 0.3) {
                // Deprioritise: this category keeps failing
                newPriority = Math.max(1, (int) (oldPriority * 0.8));
            } else if (successRate > 0.7) {
                // Boost: this category is working well
                newPriority = Math.min(100, (int) (oldPriority * 1.1));
            } else {
                continue; // 0.3–0.7: neutral, no change
            }

            if (newPriority != oldPriority) {
                reprioritise(goal.id(), newPriority);
                changed++;
            }
        }
        if (changed > 0) {
            int finalChanged = changed;
            LOG.info(() -> "Adaptive reprioritisation: " + finalChanged + " goals adjusted");
        }
        return changed;
    }

    /**
     * Return the goal's explicit category.
     * Falls back to first two words of description if category is null.
     */
    private String categorise(Goal goal) {
        return goal.category() != null ? goal.category() : "unknown";
    }

    /** Per-category success/failure tracking. */
    private static final class CategoryStats {
        private int successes;
        private int failures;

        void record(boolean success) {
            if (success) successes++; else failures++;
        }

        int total() { return successes + failures; }

        double successRate() {
            int t = total();
            return t == 0 ? 0.0 : (double) successes / t;
        }

        @Override
        public String toString() {
            return String.format("%d/%d (%.0f%%)", successes, total(), successRate() * 100);
        }
    }
}
