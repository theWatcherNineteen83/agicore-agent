package de.metis.kernel.action;

import de.metis.kernel.goal.Goal;

/**
 * Marker for actions that need the current goal context before execution.
 * <p>
 * The {@link ActionExecutor} detects implementations of this interface and
 * injects the active goal via {@link #setCurrentGoal(Goal)} right before
 * {@link Action#execute()} is called. This closes the loop between the
 * planner's goal and the effector: code-generating actions finally know
 * WHAT they are supposed to build.
 */
public interface GoalAwareAction {

    /**
     * Inject the goal that triggered this action execution.
     *
     * @param goal the currently processed goal (never null when invoked
     *             from the agent core loop)
     */
    void setCurrentGoal(Goal goal);
}
