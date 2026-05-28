package de.metis.kernel.action;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Registry and dispatcher for {@link Action} implementations.
 * <p>
 * The executor holds a map of known action types and delegates
 * {@code execute()} to the matching handler. The planner references
 * actions by name; the executor resolves the name to a concrete instance.
 * <p>
 * Extension point: register custom actions via {@link #register(Action)}.
 */
public class ActionExecutor {

    private static final Logger LOG = Logger.getLogger(ActionExecutor.class.getName());

    private final Map<String, Action> registry = new ConcurrentHashMap<>();

    /**
     * Make a new action type known to the agent.
     *
     * @param action the action instance to register
     */
    public void register(Action action) {
        registry.put(action.name(), action);
        LOG.fine(() -> "Registered action: " + action.name());
    }

    /**
     * Execute a named action.
     *
     * @param name the action's registered name
     * @return result or a failure envelope if the action is unknown
     */
    public ActionResult execute(String name) {
        Action action = registry.get(name);
        if (action == null) {
            LOG.warning(() -> "Unknown action requested: " + name);
            return ActionResult.fail(name, "Unknown action: " + name, java.time.Instant.now());
        }
        LOG.info(() -> "Executing action: " + name);
        return action.execute();
    }

    /**
     * All registered action names (for planner introspection).
     */
    public java.util.Set<String> availableActions() {
        return java.util.Set.copyOf(registry.keySet());
    }

    /**
     * Check if a named action requires human approval before execution.
     *
     * @param name the action's registered name
     * @return true if the action requires approval, false otherwise
     * @deprecated use {@link #getApprovalLevel(String)} instead
     */
    @Deprecated
    public boolean requiresApproval(String name) {
        Action.ApprovalLevel level = getApprovalLevel(name);
        return level == Action.ApprovalLevel.CONFIRM || level == Action.ApprovalLevel.FORBIDDEN;
    }

    /**
     * Get the approval level for a named action.
     * <p>
     * Returns {@link Action.ApprovalLevel#CONFIRM} for unknown actions
     * (safety-first: unknown = requires confirmation).
     *
     * @param name the action's registered name
     * @return the approval level, never null
     */
    public Action.ApprovalLevel getApprovalLevel(String name) {
        Action action = registry.get(name);
        if (action == null) return Action.ApprovalLevel.CONFIRM;
        return action.approvalLevel();
    }
}
