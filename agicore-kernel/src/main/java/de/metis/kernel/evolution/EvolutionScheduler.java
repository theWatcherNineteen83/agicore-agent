package de.metis.kernel.evolution;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Logger;

/**
 * Adaptive evolution scheduler — decides WHEN to trigger evolution
 * based on fitness trends, not fixed tick intervals.
 * <p>
 * Replaces fixed-interval "every 100 ticks" with:
 * <ul>
 *   <li>Stagnation detection: fitness flat for N measurements → trigger</li>
 *   <li>Regression detection: fitness dropping → trigger quickly</li>
 *   <li>Cool-down: minimum ticks between evolutions</li>
 *   <li>Max-gap: force trigger after long idle period</li>
 * </ul>
 * <p>
 * Evolvable: weights and thresholds can be tuned via eval-harness.
 * Georg's Review #12 — Continuous Evolution Scheduler.
 */
public class EvolutionScheduler {

    private static final Logger LOG = Logger.getLogger(EvolutionScheduler.class.getName());

    /** How many fitness measurements to keep in the sliding window. */
    private final int windowSize;

    /** Minimum ticks between evolution attempts (cool-down). */
    private final int minTicksBetween;

    /** Maximum ticks without evolution before forcing one. */
    private final int maxTicksIdle;

    /** Stagnation threshold: standard deviation below this → trigger. */
    private final double stagnationStdDev;

    /** Regression threshold: negative slope steeper than this → trigger. */
    private final double regressionSlope;

    /** Sliding window of recent fitness values. */
    private final Deque<Double> fitnessWindow;

    /** Tick of last evolution attempt. */
    private long lastEvolutionTick = -1;

    /** Tick of last successful evolution. */
    private long lastSuccessfulEvolution = -1;

    /** Number of consecutive evolutions that produced no improvement. */
    private int plateauCount = 0;

    /** Tick of last decision log (avoid spam). */
    private long lastLogTick = 0;

    public EvolutionScheduler() {
        this(20, 50, 500, 0.02, -0.001);
    }

    public EvolutionScheduler(int windowSize, int minTicksBetween, int maxTicksIdle,
                              double stagnationStdDev, double regressionSlope) {
        this.windowSize = windowSize;
        this.minTicksBetween = minTicksBetween;
        this.maxTicksIdle = maxTicksIdle;
        this.stagnationStdDev = stagnationStdDev;
        this.regressionSlope = regressionSlope;
        this.fitnessWindow = new ArrayDeque<>(windowSize);
    }

    /**
     * Record a fitness measurement.
     */
    public void recordFitness(double fitness) {
        fitnessWindow.addLast(fitness);
        if (fitnessWindow.size() > windowSize) {
            fitnessWindow.removeFirst();
        }
    }

    /**
     * Decide whether evolution should be triggered now.
     *
     * @param currentTick current agent tick counter
     * @return decision with reason and urgency
     */
    public EvolutionDecision decide(long currentTick) {
        // Cool-down check
        if (lastEvolutionTick >= 0 && currentTick - lastEvolutionTick < minTicksBetween) {
            logDecision(currentTick, "COOL-DOWN", 0.0);
            return EvolutionDecision.NO;
        }

        // Not enough data — wait
        if (fitnessWindow.size() < windowSize / 2) {
            logDecision(currentTick, "WAITING_DATA", 0.0);
            return EvolutionDecision.NO;
        }

        // Max-gap force trigger
        if (lastEvolutionTick >= 0 && currentTick - lastEvolutionTick >= maxTicksIdle) {
            logDecision(currentTick, "MAX_GAP_FORCE", urgency(0.5));
            return new EvolutionDecision(true, "Max idle gap exceeded (" + maxTicksIdle + " ticks)", 0.5);
        }

        // Compute statistics
        double[] values = fitnessWindow.stream().mapToDouble(d -> d).toArray();
        double mean = mean(values);
        double stdDev = stdDev(values, mean);
        double slope = linearSlope(values);

        // Regression detection: fitness dropping significantly
        if (slope < regressionSlope && stdDev > stagnationStdDev) {
            double urgency = Math.min(1.0, Math.abs(slope) * 100);
            logDecision(currentTick, "REGRESSION", urgency);
            return new EvolutionDecision(true, String.format(
                    "Fitness regression detected (slope=%.4f, σ=%.4f)", slope, stdDev), urgency);
        }

        // Stagnation detection: fitness flat for too long
        if (stdDev < stagnationStdDev && fitnessWindow.size() >= windowSize) {
            plateauCount++;
            if (plateauCount >= 3) {
                double urgency = 0.3;
                logDecision(currentTick, "STAGNATION", urgency);
                return new EvolutionDecision(true, String.format(
                        "Fitness stagnated for %d windows (σ=%.4f)", plateauCount + 1, stdDev), urgency);
            }
        } else {
            plateauCount = 0; // reset if variation detected
        }

        logDecision(currentTick, "STABLE", 0.0);
        return EvolutionDecision.NO;
    }

    /**
     * Notify scheduler that an evolution was attempted.
     */
    public void evolutionAttempted(long tick, boolean improved) {
        this.lastEvolutionTick = tick;
        if (improved) {
            this.lastSuccessfulEvolution = tick;
            this.plateauCount = 0;
        }
    }

    /**
     * Reset state (for testing or restart).
     */
    public void reset() {
        fitnessWindow.clear();
        lastEvolutionTick = -1;
        lastSuccessfulEvolution = -1;
        plateauCount = 0;
    }

    public int windowFill() { return fitnessWindow.size(); }
    public long lastEvolutionTick() { return lastEvolutionTick; }
    public long lastSuccessfulEvolution() { return lastSuccessfulEvolution; }

    private double urgency(double base) {
        // Escalate urgency with plateau count
        return Math.min(1.0, base + plateauCount * 0.1);
    }

    private void logDecision(long tick, String decision, double urgency) {
        if (tick - lastLogTick < 50) return; // avoid log spam
        lastLogTick = tick;
        LOG.fine(() -> String.format("EvoScheduler tick=%d decision=%s urgency=%.2f window=%d",
                tick, decision, urgency, fitnessWindow.size()));
    }

    // ── Statistics ─────────────────────────────────────────────

    private static double mean(double[] values) {
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.length;
    }

    private static double stdDev(double[] values, double mean) {
        double sumSq = 0;
        for (double v : values) sumSq += (v - mean) * (v - mean);
        return Math.sqrt(sumSq / values.length);
    }

    private static double linearSlope(double[] values) {
        // Simple linear regression slope over the window
        int n = values.length;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += values[i];
            sumXY += i * values[i];
            sumX2 += i * i;
        }
        double denom = n * sumX2 - sumX * sumX;
        if (Math.abs(denom) < 1e-10) return 0;
        return (n * sumXY - sumX * sumY) / denom;
    }

    /**
     * Evolution decision with reason and urgency.
     */
    public record EvolutionDecision(boolean shouldEvolve, String reason, double urgency) {
        public static final EvolutionDecision NO = new EvolutionDecision(false, "not triggered", 0.0);
    }
}
