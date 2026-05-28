package de.metis.kernel.eval;

/**
 * Result of scoring a single eval task run.
 *
 * @param metric  metric name (e.g. "goal_achieved", "recall@5")
 * @param value   the measured value (0.0–1.0 for rates, raw for others)
 * @param gate    HARD or SOFT
 */
public record MetricResult(String metric, double value, Gate gate) {
    public MetricResult {
        if (value < 0) throw new IllegalArgumentException("value must be >= 0, got " + value);
    }

    public boolean passed() {
        return value >= 1.0; // binary pass: 1.0 = perfect
    }
}
