package de.agicore.kernel.self;

import de.agicore.kernel.meta.MetaCognition;
import de.agicore.kernel.metrics.PerformanceMetrics;
import de.agicore.kernel.workspace.ContentItem;

import java.util.*;
import java.util.logging.Logger;

/**
 * The agent's model of itself — tracks internal state, generates
 * self-referential content for the Global Workspace, and maintains
 * self-confidence calibration.
 * <p>
 * <b>Why this matters for proto-consciousness:</b>
 * The self-model is one of the content sources that competes in the
 * Global Workspace. When self-referential content wins the competition,
 * the agent becomes "aware" of its own state — it can reflect on whether
 * it's performing well, whether it's confused, or whether it needs to
 * change strategy.
 * <p>
 * This is the foundation of meta-representation: the agent representing
 * its own internal states as objects of attention.
 */
public class SelfModel {

    private static final Logger LOG = Logger.getLogger(SelfModel.class.getName());

    private final SelfPerformanceHistory history;
    private MetaCognition meta;
    private PerformanceMetrics metrics;

    /** Confidence calibration: how accurate has our self-assessment been? */
    private double calibrationError = 0.0;
    private long calibrations = 0;

    public SelfModel() {
        this.history = new SelfPerformanceHistory();
    }

    /** Bind to live subsystems after construction. */
    public void bind(MetaCognition meta, PerformanceMetrics metrics) {
        this.meta = meta;
        this.metrics = metrics;
    }

    /**
     * Take a self-state snapshot from live subsystems and record it.
     *
     * @param attentionFocus what the agent is currently attending to
     * @return the snapshot
     */
    public SelfState snapshot(String attentionFocus) {
        if (meta == null || metrics == null) {
            LOG.warning("SelfModel not bound — returning empty snapshot");
            return new SelfState(java.time.Instant.now(),
                    0.5, 0.5, false, 0, 0, 0.5, 0.5, 0, 0, 0,
                    attentionFocus);
        }

        SelfState state = new SelfState(
                java.time.Instant.now(),
                meta.confidence(),
                meta.rollingError(),
                meta.isSurprised(),
                -1, // active goals not available directly; set by caller
                (int) metrics.successfulGoalsCount(),
                metrics.goalSuccessRate(),
                metrics.planningEfficiency(),
                metrics.resourceUsage(),
                -1, // stm size set by caller
                -1, // ltm size set by caller
                attentionFocus
        );
        history.record(state);
        LOG.fine(() -> "Self snapshot: " + state);
        return state;
    }

    /**
     * Take a full snapshot including externally provided sizes.
     */
    public SelfState fullSnapshot(int activeGoals, int stmSize, int ltmSize, String attentionFocus) {
        if (meta == null || metrics == null) {
            return snapshot(attentionFocus);
        }
        SelfState state = new SelfState(
                java.time.Instant.now(),
                meta.confidence(),
                meta.rollingError(),
                meta.isSurprised(),
                activeGoals,
                (int) metrics.successfulGoalsCount(),
                metrics.goalSuccessRate(),
                metrics.planningEfficiency(),
                metrics.resourceUsage(),
                stmSize,
                ltmSize,
                attentionFocus
        );
        history.record(state);
        return state;
    }

    /**
     * Generate self-referential content items for the Global Workspace.
     * <p>
     * These items represent the agent's "self-talk" — its ability to
     * attend to its own internal state. When one of these wins the
     * attention competition, the agent effectively "thinks about itself."
     *
     * @param activeGoals current active goal count
     * @param stmSize     short-term memory size
     * @param ltmSize     long-term memory size
     * @return content items representing self-awareness
     */
    public List<ContentItem> generateSelfContent(int activeGoals, int stmSize, int ltmSize) {
        if (meta == null || metrics == null) return List.of();

        List<ContentItem> items = new ArrayList<>();

        // 1. Health report — how am I doing?
        double health = history.latest()
                .map(SelfState::selfHealth)
                .orElse(0.5);
        double novelty = history.hasDegraded() ? 0.9 : 0.3;
        double relevance = health < 0.4 ? 0.9 : 0.3;
        items.add(new ContentItem("self",
                "Self-health: " + (health > 0.6 ? "good" : health > 0.3 ? "ok" : "poor"),
                0.7, novelty, relevance,
                "health=" + String.format("%.2f", health)
                        + " conf=" + String.format("%.2f", meta.confidence())
                        + " goals=" + activeGoals));

        // 2. Surprise alert — am I surprised?
        if (meta.isSurprised()) {
            items.add(new ContentItem("self",
                    "Surprised! High prediction error",
                    0.9, 0.8, 0.8,
                    "rollingError=" + String.format("%.2f", meta.rollingError())
                            + " stddev=" + String.format("%.2f", meta.errorStdDev())));
        }

        // 3. Performance trend — am I improving?
        double trend = history.healthTrend(20);
        if (Math.abs(trend) > 0.05) {
            String direction = trend > 0 ? "improving" : "declining";
            double sal = trend > 0 ? 0.4 : 0.7; // decline is more salient
            items.add(new ContentItem("self",
                    "Performance " + direction + " (trend=" + String.format("%+.3f", trend) + ")",
                    sal, 0.5, 0.6,
                    "trend=" + String.format("%+.3f", trend)
                            + " succ=" + String.format("%.0f%%", metrics.goalSuccessRate() * 100)));
        }

        // 4. Memory state — how full am I?
        double memRatio = (double) stmSize / Math.max(1, 100); // default STM capacity
        if (memRatio > 0.8) {
            items.add(new ContentItem("self",
                    "Memory nearly full (STM " + stmSize + ")",
                    0.6, 0.4, 0.7,
                    "stm=" + stmSize + " ltm=" + ltmSize));
        }

        // 5. Calibration self-check
        if (calibrations > 10) {
            double calib = calibrationConfidence();
            items.add(new ContentItem("self",
                    "Self-calibration: " + (calib > 0.7 ? "accurate" : "miscalibrated"),
                    0.5, 0.3, 0.4,
                    "calibrationError=" + String.format("%.3f", calibrationError)));
        }

        return items;
    }

    /**
     * Calibrate self-assessment: compare predicted confidence against
     * actual outcome. This is meta-meta-cognition.
     *
     * @param predictedSuccess whether we thought we'd succeed
     * @param actualSuccess    whether we actually succeeded
     */
    public void calibrate(boolean predictedSuccess, boolean actualSuccess) {
        calibrations++;
        double error = (predictedSuccess == actualSuccess) ? 0.0 : 1.0;
        calibrationError = 0.9 * calibrationError + 0.1 * error;
    }

    // ── Fix #6: Forward-Model (self-prediction tracking) ─────────

    /** Per-action-type error distribution for forward-model prediction. */
    private final Map<String, ForwardModelStats> forwardModels = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Record a forward-model prediction vs. actual outcome.
     * This builds a per-action-type error distribution so the self-model
     * can predict its own future performance.
     *
     * @param actionName       which action type
     * @param expectedSuccess  predicted success probability (0–1)
     * @param actualOutcome    actual outcome (0 or 1)
     * @param predictionError  |expected - actual|
     */
    public void recordForwardPrediction(String actionName, double expectedSuccess,
                                        double actualOutcome, double predictionError) {
        forwardModels.computeIfAbsent(actionName, k -> new ForwardModelStats())
                .record(expectedSuccess, actualOutcome, predictionError);
    }

    /**
     * Predict own success probability for a given action type.
     * Uses historical error distribution: if we're usually +0.1 optimistic,
     * subtract 0.1 from our estimate.
     */
    public double predictOwnSuccess(String actionName, double rawEstimate) {
        ForwardModelStats stats = forwardModels.get(actionName);
        if (stats == null || stats.count < 3) return rawEstimate;
        return Math.max(0.0, Math.min(1.0, rawEstimate - stats.averageBias()));
    }

    /** How accurate the forward model is across all action types. */
    public double forwardModelAccuracy() {
        if (forwardModels.isEmpty()) return 0.5;
        return forwardModels.values().stream()
                .mapToDouble(s -> 1.0 - s.averageError())
                .average()
                .orElse(0.5);
    }

    /** Per-action forward-model statistics. */
    private static final class ForwardModelStats {
        int count;
        double totalBias;       // Σ(expected - actual), positive = overoptimistic
        double totalError;       // Σ|expected - actual|

        void record(double expected, double actual, double error) {
            count++;
            totalBias += (expected - actual);
            totalError += error;
        }

        double averageBias() { return count == 0 ? 0 : totalBias / count; }
        double averageError() { return count == 0 ? 0 : totalError / count; }
    }

    /** How accurate our self-assessment is (1.0 = perfectly calibrated). */
    public double calibrationConfidence() {
        if (calibrations < 5) return 0.5; // not enough data
        return 1.0 - calibrationError;
    }

    public SelfPerformanceHistory history() { return history; }
}
