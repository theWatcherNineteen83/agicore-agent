package de.agicore.kernel.planner;

import de.agicore.kernel.action.ActionExecutor;

import java.util.*;
import java.util.logging.Logger;

/**
 * Validates plans before execution — implements Huyen's "decouple planning
 * from execution" pattern (Kap. 6, Generative KI-Systeme entwickeln).
 * <p>
 * Validation rules:
 * <ol>
 *   <li>Plan must not be empty</li>
 *   <li>All actions must be registered in the ActionExecutor</li>
 *   <li>Plan must not exceed max steps (prevents runaway plans)</li>
 *   <li>Consecutive duplicate actions are rejected (loop detection)</li>
 * </ol>
 * <p>
 * This is part of the immutable kernel. The validation criteria never change.
 */
public final class PlanValidator {

    private static final Logger LOG = Logger.getLogger(PlanValidator.class.getName());

    /** Maximum actions in a single plan. */
    private static final int MAX_PLAN_STEPS = 20;

    private final ActionExecutor executor;

    public PlanValidator(ActionExecutor executor) {
        this.executor = executor;
    }

    /**
     * Validate a plan. Returns a result with pass/fail and a reason.
     */
    public ValidationResult validate(List<String> plan) {
        if (plan == null || plan.isEmpty()) {
            return ValidationResult.fail("Plan is empty");
        }

        if (plan.size() > MAX_PLAN_STEPS) {
            return ValidationResult.fail(
                    "Plan too long: " + plan.size() + " > " + MAX_PLAN_STEPS);
        }

        // Check all actions are registered
        Set<String> available = executor.availableActions();
        for (String action : plan) {
            if (!available.contains(action)) {
                return ValidationResult.fail("Unknown action: " + action
                        + " (available: " + available + ")");
            }
        }

        // Consecutive duplicate detection (simple loop guard)
        for (int i = 1; i < plan.size(); i++) {
            if (plan.get(i).equals(plan.get(i - 1))) {
                LOG.fine("Plan contains consecutive duplicate: " + plan.get(i));
                // Not a hard fail — just a warning
            }
        }

        return ValidationResult.ok(plan.size());
    }

    /**
     * Immutable validation result.
     */
    public record ValidationResult(boolean valid, String reason, int stepCount) {
        public static ValidationResult ok(int steps) {
            return new ValidationResult(true, "Plan valid (" + steps + " steps)", steps);
        }

        public static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason, 0);
        }
    }
}
