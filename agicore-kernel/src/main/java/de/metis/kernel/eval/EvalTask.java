package de.metis.kernel.eval;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Immutable evaluation task definition.
 * <p>
 * Each task defines a single test case: input, ground-truth, scoring config.
 * Tasks are stored read-only (Hash-Chain integrity) in the Watchdog zone.
 * <p>
 * Design: claude_antwort_3.txt, 2026-05-28.
 *
 * @param id               unique task identifier
 * @param category         eval category
 * @param benchmarkVersion  version tag for the benchmark (breaking changes bump this)
 * @param input            the prompt/input for the agent
 * @param groundTruth      expected output or evaluation reference
 * @param scoring          how to score this task
 * @param runs             number of runs for stochastic metrics (default 1)
 * @param timeoutMs        max time before task is considered failed
 * @param heldOut          true if this task is in the secret held-out set
 */
public record EvalTask(
        String id,
        Category category,
        String benchmarkVersion,
        JsonNode input,
        GroundTruth groundTruth,
        Scoring scoring,
        int runs,
        long timeoutMs,
        boolean heldOut
) {
    public EvalTask {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(benchmarkVersion, "benchmarkVersion must not be null");
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(groundTruth, "groundTruth must not be null");
        Objects.requireNonNull(scoring, "scoring must not be null");
        if (runs < 1) throw new IllegalArgumentException("runs must be >= 1");
        if (timeoutMs < 1000) throw new IllegalArgumentException("timeoutMs must be >= 1000");
    }

    /**
     * Scoring configuration for this task.
     *
     * @param metric  metric name (e.g. "goal_achieved", "recall@5", "pass@1")
     * @param gate    HARD (blocks promotion) or SOFT (advisory)
     */
    public record Scoring(String metric, Gate gate) {
        public Scoring {
            Objects.requireNonNull(metric, "metric must not be null");
            Objects.requireNonNull(gate, "gate must not be null");
        }
    }
}
