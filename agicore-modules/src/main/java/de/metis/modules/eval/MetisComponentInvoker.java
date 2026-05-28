package de.metis.modules.eval;

import de.metis.kernel.eval.EvalTask;
import de.metis.kernel.eval.MetisOutput;
import java.util.Map;

/**
 * Abstraction for invoking Metis components during eval.
 * <p>
 * Different implementations for component-level vs end-to-end testing.
 * The invoker is injected into EvalHarness — the harness doesn't know
 * whether it's testing a single component or the full agent loop.
 */
public interface MetisComponentInvoker {

    /**
     * Invoke Metis with a single eval task input.
     *
     * @param task    the eval task (contains the prompt/input)
     * @param runIndex which run this is (0-based, for multi-run tasks)
     * @return the agent's output
     */
    MetisOutput invoke(EvalTask task, int runIndex) throws Exception;

    /**
     * Current git commit hash of the Metis codebase under test.
     */
    String currentCommit();

    /**
     * Model digests for all models used in this eval run.
     * Key: role (e.g. "planner", "codegen"), Value: "deepseek-r1:32b@edba8017"
     */
    Map<String, String> modelDigests();
}
