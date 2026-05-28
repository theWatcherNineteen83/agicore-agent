package de.metis.kernel.eval;

/**
 * Scores a single EvalTask against the agent's output.
 * <p>
 * Each category has its own scorer implementation.
 * Scorers are stateless — they only compute a metric from task + output.
 */
@FunctionalInterface
public interface Scorer {

    /**
     * Score a task run.
     *
     * @param task   the eval task (contains ground-truth and scoring config)
     * @param output the agent's output for this task
     * @return metric result (value + gate)
     */
    MetricResult score(EvalTask task, MetisOutput output);
}
