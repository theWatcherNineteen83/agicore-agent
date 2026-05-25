package de.metis.kernel.evolution;

import de.metis.kernel.metrics.PerformanceMetrics;

/**
 * Frozen fitness function — NEVER mutated.
 * <p>
 * Evaluates agent performance across four dimensions:
 * <ol>
 *   <li>Goal success rate (40%) — did we achieve what we attempted?</li>
 *   <li>Planning efficiency (30%) — did our plans produce actions?</li>
 *   <li>Prediction stability (20%) — inverse of metacognitive error volatility</li>
 *   <li>Attention diversity (10%) — workspace entropy (anti-echo-chamber)</li>
 * </ol>
 * <p>
 * This class is {@code final}. It must not be modified by the evolution
 * pipeline. The fitness formula is the invariant against which all
 * mutations are judged.
 */
public final class FitnessFunction {

    private FitnessFunction() { /* utility class */ }

    /**
     * Compute fitness score from performance metrics.
     *
     * @param m       performance metrics snapshot
     * @param entropy current workspace attention entropy
     * @return fitness score 0.0–1.0 (higher = better)
     */
    public static double evaluate(PerformanceMetrics m, double entropy) {
        double successWeight = 0.4;
        double planningWeight = 0.3;
        double stabilityWeight = 0.2;
        double entropyWeight = 0.1;

        double errorVolatility = 1.0; // placeholder: inverse of error stddev
        double stability = Math.max(0.0, 1.0 - errorVolatility);

        // Normalised entropy: map [0, 2.5] to [0, 1]
        double normEntropy = Math.min(1.0, entropy / 2.5);

        return successWeight * m.goalSuccessRate()
                + planningWeight * m.planningEfficiency()
                + stabilityWeight * stability
                + entropyWeight * normEntropy;
    }

    /** Minimum improvement threshold for a mutation to be accepted. */
    public static double minImprovement() {
        return 0.001; // 0.1% absolute improvement (gelockert für Exploration)
    }
}
