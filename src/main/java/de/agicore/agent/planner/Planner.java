package de.agicore.agent.planner;

import de.agicore.agent.action.ActionResult;
import de.agicore.agent.goal.Goal;
import de.agicore.agent.memory.Experience;
import de.agicore.agent.meta.MetaCognition;
import de.agicore.agent.workspace.ContentItem;

import java.util.List;

/**
 * Planner interface — maps a goal + context → a sequence of action names.
 * <p>
 * The planner is the agent's "thinking" step. In Phase 1 this is a stub
 * because no LLM is integrated. In Phase 2 it gains reasoning via Ollama.
 * <p>
 * Design decision: the interface returns action <em>names</em> (strings),
 * not action instances. This keeps the planner decoupled from the action
 * registry and allows the {@code AgentCoreLoop} to handle dispatch.
 * <p>
 * Extension points:
 * <ul>
 *   <li>LLM-based planner (Phase 2)</li>
 *   <li>Hierarchical planning (sub-goals)</li>
 *   <li>Reactive replanning on prediction error spike</li>
 * </ul>
 */
public interface Planner {

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
