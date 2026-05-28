package de.metis.modules.planner;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import java.util.*;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Action wrapper for {@link PromptChainingService}.
 * <p>
 * When selected by the planner, this action:
 * <ol>
 *   <li>Decomposes a complex goal into sequential sub-steps via LLM</li>
 *   <li>Executes each sub-step using the provided action executor callback</li>
 *   <li>Feeds results from step N into step N+1 as context</li>
 *   <li>Returns the synthesized chain result</li>
 * </ol>
 * <p>
 * This implements the Prompt Chaining pattern from "Prompting-Kurz&Gut":
 * multi-step reasoning where each step's output becomes the next step's input.
 *
 * @since Phase 5 (28.05.2026)
 */
public class PromptChainAction implements Action {

    private static final Logger LOG = Logger.getLogger(PromptChainAction.class.getName());

    private final PromptChainingService service;
    private final String complexGoal;
    private final Set<String> availableActions;
    private final Function<String, ActionResult> subActionExecutor;
    private final String initialContext;

    /**
     * Create a prompt chain action.
     *
     * @param service            the chaining service (Ollama-backed)
     * @param complexGoal        the high-level goal to decompose
     * @param availableActions   action names available for sub-steps
     * @param subActionExecutor  callback to execute a sub-action by name (returns ActionResult)
     * @param initialContext     optional initial context (world state, previous results)
     */
    public PromptChainAction(PromptChainingService service,
                             String complexGoal,
                             Set<String> availableActions,
                             Function<String, ActionResult> subActionExecutor,
                             String initialContext) {
        this.service = service;
        this.complexGoal = complexGoal;
        this.availableActions = Set.copyOf(availableActions);
        this.subActionExecutor = subActionExecutor;
        this.initialContext = initialContext != null ? initialContext : "";
    }

    @Override
    public String name() {
        return "prompt-chain";
    }

    @Override
    public String category() {
        return "write"; // can change world state through sub-actions
    }

    @Override
    public ActionResult execute() {
        long startMs = System.currentTimeMillis();
        StringBuilder log = new StringBuilder();

        try {
            // Phase 1: Decompose
            log.append("DECOMPOSE: '").append(truncate(complexGoal, 80)).append("'\n");
            PromptChainingService.ChainResult chain = service.decompose(
                    complexGoal, availableActions, initialContext);
            log.append("  → ").append(chain.totalSteps).append(" steps\n");

            if (chain.totalSteps == 0) {
                return ActionResult.failure("prompt-chain",
                        new IllegalStateException("Decomposition produced 0 steps"),
                        log.toString());
            }

            // Phase 2: Execute chain
            StringBuilder chainContext = new StringBuilder(initialContext);
            int successCount = 0;
            int failCount = 0;

            for (int i = 0; i < chain.steps.size(); i++) {
                PromptChainingService.ChainStep step = chain.steps.get(i);
                log.append("  STEP ").append(i + 1).append("/").append(chain.totalSteps)
                        .append(": ").append(step.description)
                        .append(" [").append(step.action).append("]");

                // Add previous results as context
                String stepContext = chainContext.toString();

                // Execute the sub-action
                ActionResult result;
                try {
                    result = subActionExecutor.apply(step.action);
                } catch (Exception e) {
                    result = ActionResult.failure(step.action, e, "Sub-action threw exception");
                }

                if (result.success()) {
                    successCount++;
                    log.append(" ✓ (").append(result.durationMs()).append("ms)\n");
                } else {
                    failCount++;
                    log.append(" ✗ (").append(result.durationMs()).append("ms): ")
                            .append(truncate(result.summary(), 100)).append("\n");
                }

                // Feed result into context for next step
                String stepResultText = result.body() != null ? result.body() : result.summary();
                chainContext.append("\n[Step ").append(i + 1).append(" result]: ")
                        .append(stepResultText).append("\n");

                service.recordStepResult(chain, i, stepResultText, result.success());
            }

            log.append("  EXECUTED: ").append(successCount).append("/").append(chain.totalSteps)
                    .append(" steps succeeded (").append(failCount).append(" failed)\n");

            // Phase 3: Synthesize
            log.append("SYNTHESIZE: aggregating chain results\n");
            String synthesis = service.synthesize(chain);
            chain.synthesizedResult = synthesis;
            log.append("  → ").append(truncate(synthesis, 150)).append("\n");

            long durationMs = System.currentTimeMillis() - startMs;
            log.append("DONE: ").append(durationMs).append("ms total\n");

            String body = String.format("""
                    {
                      "chain_id": "%s",
                      "goal": "%s",
                      "steps": %d,
                      "succeeded": %d,
                      "failed": %d,
                      "synthesis": "%s"
                    }
                    """,
                    chain.id, escapeJsonString(complexGoal),
                    chain.totalSteps, successCount, failCount,
                    escapeJsonString(synthesis));

            return new ActionResult("prompt-chain", true, body, durationMs,
                    null, log.toString());

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            LOG.warning("PromptChainAction failed: " + e.getMessage());
            return ActionResult.failure("prompt-chain", e, log.toString());
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }

    private static String escapeJsonString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
