package de.metis.kernel.action;

/**
 * A unit of work the agent can execute.
 * <p>
 * Actions are the agent's effectors — they change the world (run a command,
 * call an API) or gather information. Every action must declare its expected
 * cost so the planner can make informed trade-offs.
 * <p>
 * Extension point: add new action types by implementing this interface and
 * registering them with the {@link ActionExecutor}.
 */
public interface Action {

    /**
     * Descriptive label for logging and goal tracing.
     */
    String name();

    /**
     * Category: "read" (observing) or "write" (changing).
     * Defaults to "read" — override for write actions.
     */
    default String category() { return "read"; }

    /**
     * Whether this action requires human approval before execution.
     * Default: true for write-category actions (safety-first).
     * Override to relax for safe write actions (e.g., appending to log).
     */
    default boolean requiresApproval() { return "write".equals(category()); }

    /**
     * Execute this action and return the result.
     * <p>
     * Implementations must be self-contained: the caller provides no context
     * other than what the action already holds in its fields.
     *
     * @return structured result (exit code, body, timing, exception)
     */
    ActionResult execute();
}
