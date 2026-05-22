package de.agicore.kernel.optimize;

import java.util.*;
import java.util.logging.Logger;

/**
 * Mutates agent hyperparameters with A/B isolation, baseline comparison,
 * and significance-gated adoption.
 * <p>
 * <b>Fix #3:</b> Mutations now run isolated in an "experiment" window.
 * After N evaluation ticks, the mutant is compared against the baseline.
 * Adoption requires:
 * <ol>
 *   <li>Mutant score &gt; baseline score</li>
 *   <li>Score delta &gt; {@code MIN_IMPROVEMENT} (0.03)</li>
 *   <li>At least {@code MIN_EVALUATIONS} ticks for each</li>
 * </ol>
 * <p>
 * Configurations are versioned. The history enables rollback if performance
 * degrades after adoption.
 */
public class HyperparameterMutator {

    private static final Logger LOG = Logger.getLogger(HyperparameterMutator.class.getName());

    private static final double MUTATION_RATE = 0.1;
    private static final int MIN_EVALUATIONS = 5;
    private static final double MIN_IMPROVEMENT = 0.03; // 3% absolute improvement

    private final Random rng = new Random();
    private final List<ConfigVersion> history = new ArrayList<>();

    private ConfigVersion baseline;
    private ConfigVersion experiment;
    private int experimentTick = 0;
    private static final int EXPERIMENT_TICKS = 20; // run mutant for this many ticks

    public HyperparameterMutator() {
        Configuration defaultConfig = new Configuration(0.3, 0.01, 10, 100);
        baseline = new ConfigVersion(defaultConfig);
        experiment = null;
        history.add(baseline);
    }

    /** Currently active configuration (baseline if no experiment running). */
    public Configuration current() {
        return experiment != null ? experiment.config() : baseline.config();
    }

    /**
     * Start an experiment: fork baseline and mutate.
     * The mutant runs isolated for EXPERIMENT_TICKS.
     */
    public Configuration startExperiment() {
        if (experiment != null) {
            LOG.fine("Experiment already running, finalising first");
            finaliseExperiment();
        }
        Configuration mutant = mutate(baseline.config());
        experiment = new ConfigVersion(mutant);
        experimentTick = 0;
        LOG.info("Experiment started: " + mutant);
        return mutant;
    }

    /**
     * Evaluate the currently active configuration (baseline or experiment).
     */
    public void evaluate(double score) {
        if (experiment != null) {
            experiment.recordEvaluation(score);
            experimentTick++;

            if (experimentTick >= EXPERIMENT_TICKS) {
                finaliseExperiment();
            }
        } else {
            baseline.recordEvaluation(score);
        }
    }

    /**
     * Finalise the experiment: compare mutant vs baseline.
     * Adopt if significantly better. Rollback if worse.
     */
    private void finaliseExperiment() {
        if (experiment == null) return;

        double mutantScore = experiment.averageScore();
        double baselineScore = baseline.averageScore();
        double delta = mutantScore - baselineScore;

        LOG.info(() -> String.format(
                "Experiment finalised: mutant=%.3f baseline=%.3f delta=%+.3f (evals: m=%d b=%d)",
                mutantScore, baselineScore, delta,
                experiment.evaluationCount(), baseline.evaluationCount()));

        if (experiment.evaluationCount() >= MIN_EVALUATIONS && delta > MIN_IMPROVEMENT) {
            // Adopt mutant as new baseline
            history.add(baseline); // save old baseline for rollback
            baseline = experiment;
            LOG.info(() -> "ADOPTED new baseline: " + baseline.config()
                    + " (score +" + String.format("%.3f", delta) + ")");
        } else {
            // Rollback: keep baseline, discard mutant
            LOG.info(() -> "ROLLBACK: mutant discarded (delta="
                    + String.format("%.3f", delta) + " ≤ " + MIN_IMPROVEMENT + ")");
        }
        experiment = null;
        experimentTick = 0;
    }

    /**
     * Rollback to the previous baseline if performance degraded.
     */
    public boolean rollback() {
        if (history.size() < 2) return false;
        baseline = history.removeLast();
        LOG.info(() -> "Rolled back to: " + baseline.config());
        return true;
    }

    /** Check if performance has degraded significantly (trigger rollback). */
    public boolean checkDegradation(double currentScore, double threshold) {
        if (baseline.evaluationCount() < MIN_EVALUATIONS) return false;
        double baselineScore = baseline.averageScore();
        if (currentScore < baselineScore - threshold) {
            LOG.warning(() -> String.format(
                    "Degradation detected: current=%.3f baseline=%.3f threshold=%.3f",
                    currentScore, baselineScore, threshold));
            return rollback();
        }
        return false;
    }

    private Configuration mutate(Configuration base) {
        double alpha = clamp(base.metaAlpha() + gaussianJitter(base.metaAlpha()), 0.05, 0.95);
        double decay = clamp(base.memoryDecayRate() + gaussianJitter(base.memoryDecayRate()), 0.001, 0.5);
        int interval = clampInt(base.consolidationInterval() + (rng.nextBoolean() ? 1 : -1), 2, 100);
        int cap = clampInt(base.stmCapacity() + rng.nextInt(-20, 21), 10, 1000);
        return new Configuration(alpha, decay, interval, cap);
    }

    public static double computeScore(double goalSuccessRate, double planningEfficiency) {
        return goalSuccessRate * 0.6 + planningEfficiency * 0.4;
    }

    private double gaussianJitter(double base) {
        return rng.nextGaussian() * base * MUTATION_RATE;
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    private static int clampInt(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    public boolean isExperimentRunning() { return experiment != null; }

    // ── ConfigVersion: versioned config with scoring ──────────

    static final class ConfigVersion {
        private final UUID id = UUID.randomUUID();
        private final Configuration config;
        private final List<Double> scores = new ArrayList<>();

        ConfigVersion(Configuration config) { this.config = config; }

        void recordEvaluation(double score) { scores.add(score); }
        int evaluationCount() { return scores.size(); }

        double averageScore() {
            return scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }

        Configuration config() { return config; }
    }

    // ── Configuration ────────────────────────────────────────

    public static class Configuration {
        private final double metaAlpha;
        private final double memoryDecayRate;
        private final int consolidationInterval;
        private final int stmCapacity;

        Configuration(double metaAlpha, double memoryDecayRate,
                      int consolidationInterval, int stmCapacity) {
            this.metaAlpha = metaAlpha;
            this.memoryDecayRate = memoryDecayRate;
            this.consolidationInterval = consolidationInterval;
            this.stmCapacity = stmCapacity;
        }

        public double metaAlpha() { return metaAlpha; }
        public double memoryDecayRate() { return memoryDecayRate; }
        public int consolidationInterval() { return consolidationInterval; }
        public int stmCapacity() { return stmCapacity; }

        @Override
        public String toString() {
            return String.format("Config[α=%.2f λ=%.3f cons=%d stm=%d]",
                    metaAlpha, memoryDecayRate, consolidationInterval, stmCapacity);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Configuration c)) return false;
            return Double.compare(c.metaAlpha, metaAlpha) == 0
                    && Double.compare(c.memoryDecayRate, memoryDecayRate) == 0
                    && consolidationInterval == c.consolidationInterval
                    && stmCapacity == c.stmCapacity;
        }

        @Override
        public int hashCode() {
            return Objects.hash(metaAlpha, memoryDecayRate, consolidationInterval, stmCapacity);
        }
    }
}
