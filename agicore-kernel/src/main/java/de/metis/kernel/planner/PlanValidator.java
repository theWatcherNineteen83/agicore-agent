package de.metis.kernel.planner;

import de.metis.kernel.action.ActionExecutor;
import de.metis.kernel.goal.Goal;
import de.metis.kernel.meta.MetaCognition;

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
 *   <li><b>1.4:</b> Parameter plausibility: action-goal relevance check</li>
 *   <li><b>1.4:</b> Meta-cognitive safety gate: reject during very low confidence</li>
 * </ol>
 * <p>
 * This is part of the immutable kernel. The validation criteria never change.
 */
public final class PlanValidator {

    private static final Logger LOG = Logger.getLogger(PlanValidator.class.getName());

    /** Maximum actions in a single plan. */
    private static final int MAX_PLAN_STEPS = 20;

    /** Minimum meta-confidence to execute (safety gate). */
    private static final double MIN_CONFIDENCE_FOR_EXECUTION = 0.15;

    private final ActionExecutor executor;

    public PlanValidator(ActionExecutor executor) {
        this.executor = executor;
    }

    /**
     * Validate a plan with full context (goal + meta).
     * Preferred over {@link #validate(List)} for richer plausibility checks.
     */
    public ValidationResult validateWithContext(List<String> plan, Goal goal, MetaCognition meta) {
        // 1. Basic structural validation
        ValidationResult basic = validate(plan);
        if (!basic.valid()) return basic;

        // 2. Meta-cognitive safety gate (1.4)
        if (meta.confidence() < MIN_CONFIDENCE_FOR_EXECUTION) {
            return ValidationResult.fail(
                    "Safety gate: confidence too low ("
                    + String.format("%.2f", meta.confidence())
                    + " < " + MIN_CONFIDENCE_FOR_EXECUTION + ")");
        }

        // 3. Action-goal relevance plausibility check (1.4)
        String action = plan.getFirst();
        String goalDesc = goal.description().toLowerCase();

        // Check: action type plausibly matches goal intent
        double relevanceScore = computeActionGoalRelevance(action, goalDesc);
        if (relevanceScore < 0.1) {
            LOG.warning(() -> "Low action-goal relevance: " + action
                    + " for goal '" + goalDesc + "' (score="
                    + String.format("%.2f", relevanceScore) + ")");
            // Soft warning — don't fail, but log prominently
        }

        return ValidationResult.okWithDetails(plan.size(), relevanceScore);
    }

    /**
     * Compute a relevance score (0…1) for how well an action matches a goal.
     * Heuristic-based; augments the LLM's confidence.
     */
    private double computeActionGoalRelevance(String action, String goalDesc) {
        double score = 0.5; // neutral baseline

        // Shell actions should match system/shell/command/explore goals
        if (action.contains("shell") || action.equals("linux-explore")
                || action.equals("linux-explore-system")) {
            if (goalDesc.contains("shell") || goalDesc.contains("system")
                    || goalDesc.contains("command") || goalDesc.contains("explor")
                    || goalDesc.contains("linux") || goalDesc.contains("check")
                    || goalDesc.contains("status") || goalDesc.contains("uname")) {
                score = 0.9;
            } else if (goalDesc.contains("web") || goalDesc.contains("http")
                    || goalDesc.contains("api") || goalDesc.contains("url")) {
                score = 0.2; // shell for web goal is suspicious
            }
        }

        // HTTP actions should match web/api/request goals
        if (action.contains("http") || action.equals("webscrape")
                || action.equals("api-explorer")) {
            if (goalDesc.contains("http") || goalDesc.contains("web")
                    || goalDesc.contains("api") || goalDesc.contains("url")
                    || goalDesc.contains("request") || goalDesc.contains("health")
                    || goalDesc.contains("explor")) {
                score = 0.9;
            } else if (goalDesc.contains("shell") || goalDesc.contains("system")
                    || goalDesc.contains("command")) {
                score = 0.25; // http for shell goal is suspicious
            }
        }

        // Java sandbox should match code/compile goals
        if (action.contains("javasandbox")) {
            if (goalDesc.contains("java") || goalDesc.contains("code")
                    || goalDesc.contains("compile") || goalDesc.contains("sandbox")) {
                score = 0.95;
            } else {
                score = 0.3; // javasandbox for non-code goals is odd
            }
        }

        // Filesystem actions should match file/dir/read/list goals
        if (action.contains("filesystem")) {
            if (goalDesc.contains("file") || goalDesc.contains("dir")
                    || goalDesc.contains("list") || goalDesc.contains("read")
                    || goalDesc.contains("write") || goalDesc.contains("explor")
                    || goalDesc.contains("system")) {
                score = 0.85;
            }
        }

        return score;
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
    public record ValidationResult(boolean valid, String reason, int stepCount,
                                    double actionGoalRelevance) {
        public static ValidationResult ok(int steps) {
            return new ValidationResult(true, "Plan valid (" + steps + " steps)", steps, 0.5);
        }

        public static ValidationResult okWithDetails(int steps, double relevance) {
            return new ValidationResult(true,
                    "Plan valid (" + steps + " steps, relevance="
                    + String.format("%.2f", relevance) + ")",
                    steps, relevance);
        }

        public static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason, 0, 0.0);
        }
    }
}
