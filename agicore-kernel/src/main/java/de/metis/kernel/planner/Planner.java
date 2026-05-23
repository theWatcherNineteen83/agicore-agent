package de.metis.kernel.planner;

import de.metis.kernel.action.ActionResult;
import de.metis.kernel.goal.Goal;
import de.metis.kernel.memory.Experience;
import de.metis.kernel.meta.MetaCognition;
import de.metis.kernel.workspace.ContentItem;

import java.util.List;

/**
 * Planner interface — maps a goal + context → a sequence of action names.
 * <p>
 * Extends {@link EvolvableModule}: planner implementations are evolvable.
 * The kernel depends only on this interface, never on concrete implementations.
 */
public interface Planner extends EvolvableModule {

    /**
     * Produce a plan (ordered list of action names) to achieve a goal.
     *
     * @param goal          the active goal
     * @param recentHistory recent experiences for context
     * @param broadcast     current Global Workspace broadcast (attention winners)
     * @param meta          current metacognitive state
     * @return ordered action names; empty list = no plan possible
     */
    List<String> plan(Goal goal, List<Experience> recentHistory,
                      List<ContentItem> broadcast, MetaCognition meta);

    /**
     * Estimate the expected success probability of an action for a goal.
     * Used to compute prediction error: |expected - actual|.
     * Default returns 0.5 (maximum uncertainty).
     */
    default double expectedSuccess(Goal goal, String actionName) {
        return 0.5;
    }

    /**
     * Phase 2 improvement hook: called after an action completes so the
     * planner can learn from the outcome and adjust its strategy.
     * <p>
     * Default implementation is a no-op. Planner implementations override
     * this to implement self-improvement.
     *
     * @param goal   the goal that was pursued
     * @param plan   the plan that was produced (may be empty)
     * @param result the actual outcome of execution (may be null if no action ran)
     */
    default void learnFromOutcome(Goal goal, List<String> plan, ActionResult result) {
        // no-op by default
    }
}
