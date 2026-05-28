package de.metis.kernel.action;

/**
 * A unit of work the agent can execute.
 * <p>
 * Actions are the agent's effectors — they change the world (run a command,
 * call an API) or gather information. Every action must declare its expected
 * cost so the planner can make informed trade-offs.
 * <p>
 * <b>Human-in-the-Loop (Huyen Kap.6):</b> Each action declares an
 * {@link ApprovalLevel} that defines how much automation it is allowed.
 * The core loop enforces this based on the configured maximum auto-approval
 * threshold.
 * <p>
 * Extension point: add new action types by implementing this interface and
 * registering them with the {@link ActionExecutor}.
 */
public interface Action {

    /**
     * Approval level defining how much automation an action is allowed.
     * <p>
     * Huyen Kap.6: "definieren, wie viel Automation ein Agent für
     * jede Aktion besitzen darf."
     */
    enum ApprovalLevel {
        /** Fully automatic — no human intervention needed (read-only). */
        AUTO,
        /** Logged but auto-executed (safe writes with reversible side effects). */
        NOTIFY,
        /** Requires human confirmation before execution (risky writes). */
        CONFIRM,
        /** Never auto-executed — always requires explicit human approval. */
        FORBIDDEN
    }

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
     *
     * @deprecated use {@link #approvalLevel()} instead
     */
    @Deprecated
    default boolean requiresApproval() {
        return approvalLevel() == ApprovalLevel.CONFIRM
                || approvalLevel() == ApprovalLevel.FORBIDDEN;
    }

    /**
     * The approval level for this action.
     * <p>
     * Default mapping:
     * <ul>
     *   <li>"read" category → {@link ApprovalLevel#AUTO}</li>
     *   <li>"write" category → {@link ApprovalLevel#CONFIRM}</li>
     * </ul>
     * Override to set a different level (e.g., safe writes → NOTIFY,
     * critical writes → FORBIDDEN).
     *
     * @return the approval level for this action
     */
    default ApprovalLevel approvalLevel() {
        return "write".equals(category()) ? ApprovalLevel.CONFIRM : ApprovalLevel.AUTO;
    }

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
