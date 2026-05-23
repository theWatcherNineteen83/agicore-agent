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
     * Execute this action and return the result.
     * <p>
     * Implementations must be self-contained: the caller provides no context
     * other than what the action already holds in its fields.
     *
     * @return structured result (exit code, body, timing, exception)
     */
    ActionResult execute();
}
