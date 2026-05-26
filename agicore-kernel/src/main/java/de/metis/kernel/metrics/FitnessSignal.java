package de.metis.kernel.metrics;

import java.util.*;
import java.util.logging.Logger;

/**
 * Multidimensional fitness signal for Metis evolution.
 * <p>
 * Computes a single fitness value from four dimensions using
 * geometric mean aggregation with hard safety floors.
 * <p>
 * Design: Claude AI review, 2026-05-26 — based on:
 * - Huyen: "Evaluating Generative AI Systems" (Chapter 6)
 * - Pearl: causal reasoning principles
 * <p>
 * Requires calibration before evolution can start.
 */
public class FitnessSignal {

    private static final Logger LOG = Logger.getLogger(FitnessSignal.class.getName());
    private static final double EPSILON = 1e-9;
    private static final double ALPHA = 0.1;  // EMA smoothing factor

    // EMA state (smoothed across ticks)
    private double emaCE = 0.5;
    private double emaGP = 0.5;
    private double emaWMA = 0.5;
    private double emaMC = 0.5;
    private double emaFitness = 0.5;

    // Calibration (from bootstrap phase)
    private final List<Double> predictionErrorHistory = new ArrayList<>();
    private final List<Double> fitnessHistory = new ArrayList<>();
    private final List<Double> surpriseHistory = new ArrayList<>();
    private boolean calibrated = false;

    private double maxPredictionError = 1.0;
    private double baselineFitness = 0.5;
    private double acceptThreshold = 0.55;
    private double targetSurprise = 0.3;

    /**
     * Computation result with all dimension details.
     */
    public record FitnessSnapshot(
            double cognitiveEfficiency,
            double goalPerformance,
            double worldModelAccuracy,
            double metacognitiveCalibration,
            double rawFitness,
            double smoothFitness,
            boolean calibrationComplete
    ) {
        public String summary() {
            return String.format("CE=%.2f GP=%.2f WMA=%.2f MC=%.2f → fitness=%.3f (EMA:%.3f) calib=%s",
                    cognitiveEfficiency, goalPerformance, worldModelAccuracy,
                    metacognitiveCalibration, rawFitness, smoothFitness, calibrationComplete);
        }
    }

    /**
     * Compute a fitness snapshot from raw agent metrics.
     */
    public FitnessSnapshot compute(AgentMetrics m) {
        double CE  = computeCE(m.cpuLoad(), m.heapPressure(), m.goalCompletionRate());
        double GP  = computeGP(m.weightedGoalCompletion(), m.totalGoalComplexity());
        double WMA = computeWMA(m.predictionError());
        double MC  = computeMC(m.surpriseValue());

        // EMA smoothing
        emaCE  = ALPHA * CE  + (1 - ALPHA) * emaCE;
        emaGP  = ALPHA * GP  + (1 - ALPHA) * emaGP;
        emaWMA = ALPHA * WMA + (1 - ALPHA) * emaWMA;
        emaMC  = ALPHA * MC  + (1 - ALPHA) * emaMC;

        double raw = aggregate(emaCE, emaGP, emaWMA, emaMC);
        emaFitness = ALPHA * raw + (1 - ALPHA) * emaFitness;

        // Record history during calibration
        if (!calibrated) {
            recordCalibrationData(m.predictionError(), raw, m.surpriseValue());
        }

        return new FitnessSnapshot(emaCE, emaGP, emaWMA, emaMC, raw, emaFitness, calibrated);
    }

    // ── Individual dimensions ──────────────────────────────────────

    /** Cognitive Efficiency: resource cost per unit of output. */
    private double computeCE(double cpu, double heap, double gcr) {
        double load = 0.6 * cpu + 0.4 * heap;
        double ratio = gcr / (load + EPSILON);
        return sigmoid(ratio, 1.0, 3.0);
    }

    /** Goal Performance: weighted completion rate (trivial goals count less). */
    private double computeGP(double weightedCompletion, double totalComplexity) {
        return Math.min(weightedCompletion / (totalComplexity + EPSILON), 1.0);
    }

    /** World-Model Accuracy: inverted EMA prediction error. */
    private double computeWMA(double predError) {
        if (calibrated) {
            double norm = Math.min(predError / (maxPredictionError + EPSILON), 1.0);
            return 1.0 - norm;
        }
        // During calibration: optimistic default
        return 0.5;
    }

    /** Metacognitive Calibration: surprise near target is good. */
    private double computeMC(double surprise) {
        double deviation = Math.abs(surprise - targetSurprise);
        return Math.max(0, 1.0 - deviation / targetSurprise);
    }

    // ── Aggregation ─────────────────────────────────────────────────

    private double aggregate(double CE, double GP, double WMA, double MC) {
        // Hard floors: blind or resource-collapsed agent has zero fitness
        if (WMA < 0.2 || CE < 0.1) return 0.0;

        // Geometric mean: one weak dimension pulls everything down
        double fitness = Math.pow(CE,  0.25)
                       * Math.pow(GP,  0.35)
                       * Math.pow(WMA, 0.25)
                       * Math.pow(MC,  0.15);

        // Consistency bonus: stable signal is slightly rewarded
        double consistency = computeConsistency();
        return Math.min(fitness * (1.0 + 0.1 * consistency), 1.0);
    }

    private double computeConsistency() {
        double expected = emaCE * 0.25 + emaGP * 0.35 + emaWMA * 0.25 + emaMC * 0.15;
        double variance = Math.abs(emaFitness - expected);
        return Math.max(0, 1.0 - variance * 5.0);
    }

    // ── Calibration ─────────────────────────────────────────────────

    private void recordCalibrationData(double predError, double fitness, double surprise) {
        predictionErrorHistory.add(predError);
        fitnessHistory.add(fitness);
        surpriseHistory.add(surprise);

        // Auto-calibrate after enough samples
        if (predictionErrorHistory.size() >= 200) {
            calibrate();
        }
    }

    private void calibrate() {
        if (predictionErrorHistory.size() < 50) return; // need minimum samples

        maxPredictionError = percentile(predictionErrorHistory, 0.95);
        baselineFitness = mean(fitnessHistory);
        acceptThreshold = baselineFitness * 1.05;
        targetSurprise = percentile(surpriseHistory, 0.50);

        calibrated = true;
        LOG.info(String.format(
                "Fitness calibration complete (%d samples): maxPredError=%.3f, baseline=%.3f, accept=%.3f, targetSurprise=%.3f",
                predictionErrorHistory.size(), maxPredictionError,
                baselineFitness, acceptThreshold, targetSurprise));
    }

    /** Force calibration (for testing or agent restart with saved data). */
    public void forceCalibration(double maxPredError, double baseline, double accept, double targetSurp) {
        this.maxPredictionError = maxPredError;
        this.baselineFitness = baseline;
        this.acceptThreshold = accept;
        this.targetSurprise = targetSurp;
        this.calibrated = true;
        LOG.info("Fitness calibration restored from saved state");
    }

    // ── Accessors ───────────────────────────────────────────────────

    public boolean isCalibrated() { return calibrated; }
    public double baselineFitness() { return baselineFitness; }
    public double acceptThreshold() { return acceptThreshold; }
    public double targetSurprise() { return targetSurprise; }
    public double currentFitness() { return emaFitness; }
    public int calibrationSamples() { return predictionErrorHistory.size(); }

    // ── Math helpers ────────────────────────────────────────────────

    private double sigmoid(double x, double mid, double k) {
        return 1.0 / (1.0 + Math.exp(-k * (x - mid)));
    }

    private double mean(List<Double> values) {
        return values.stream().mapToDouble(v -> v).average().orElse(0.5);
    }

    private double percentile(List<Double> values, double p) {
        if (values.isEmpty()) return 0;
        var sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int idx = (int) (p * (sorted.size() - 1));
        return sorted.get(Math.min(idx, sorted.size() - 1));
    }

    /**
     * Simple metrics snapshot passed from the agent runtime.
     */
    public record AgentMetrics(
            double cpuLoad,
            double heapPressure,
            double goalCompletionRate,
            double weightedGoalCompletion,
            double totalGoalComplexity,
            double predictionError,
            double surpriseValue
    ) {}
}
