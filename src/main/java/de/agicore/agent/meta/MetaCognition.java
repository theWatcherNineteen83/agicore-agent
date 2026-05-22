package de.agicore.agent.meta;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Self-monitoring module that tracks the agent's prediction accuracy
 * and maintains a running confidence estimate.
 * <p>
 * Core metrics:
 * <ul>
 *   <li><b>Rolling prediction error</b> — exponential moving average
 *       of recent prediction errors. High → agent is surprised often.</li>
 *   <li><b>Confidence</b> — inverse of rolling error. Ranges 0.0–1.0.
 *       Used by the planner to decide whether to act or gather more info.</li>
 * </ul>
 * <p>
 * Extension points:
 * <ul>
 *   <li>Per-action-type error tracking</li>
 *   <li>Per-goal-domain error tracking</li>
 *   <li>Confidence calibration against ground truth</li>
 * </ul>
 */
public class MetaCognition {

    private static final Logger LOG = Logger.getLogger(MetaCognition.class.getName());

    /** Smoothing factor for the EMA (higher = more weight on recent). */
    private static final double DEFAULT_ALPHA = 0.3;
    /** Maximum confidence — never 1.0 (prevents overconfidence drift). */
    private static final double MAX_CONFIDENCE = 0.95;
    /** Minimum observation noise to prevent perfect calibration. */
    private static final double MIN_OBSERVATION_NOISE = 0.01;

    /** Window for computing standard deviation. */
    private static final int WINDOW_SIZE = 50;

    private final double alpha;
    private double rollingError = 0.0;
    private double rollingErrorStdDev = 0.0;
    private long observationCount = 0;

    /** Rolling window of recent errors for stddev computation. */
    private final List<Double> recentErrors = new ArrayList<>();

    public MetaCognition() {
        this(DEFAULT_ALPHA);
    }

    public MetaCognition(double alpha) {
        this.alpha = alpha;
    }

    /**
     * Record a new prediction error observation.
     * <p>
     * Updates the exponential moving average and the rolling window.
     *
     * @param predictionError 0.0–1.0, how wrong the prediction was
     */
    public synchronized void observe(double predictionError) {
        // Inject minimum noise to prevent perfect convergence
        double noisyError = predictionError + MIN_OBSERVATION_NOISE * (Math.random() - 0.5);
        if (observationCount == 0) {
            rollingError = noisyError;
        } else {
            rollingError = alpha * noisyError + (1.0 - alpha) * rollingError;
        }
        observationCount++;

        recentErrors.add(predictionError);
        if (recentErrors.size() > WINDOW_SIZE) {
            recentErrors.removeFirst();
        }
        rollingErrorStdDev = computeStdDev();

        LOG.fine(() -> String.format("Meta: error=%.3f rolling=%.3f±%.3f n=%d",
                predictionError, rollingError, rollingErrorStdDev, observationCount));
    }

    /**
     * Current confidence estimate.
     * <pre>confidence = max(0, 1.0 − rollingError)</pre>
     */
    public synchronized double confidence() {
        return Math.min(MAX_CONFIDENCE, Math.max(0.0, 1.0 - rollingError));
    }

    /** EMA of prediction error. */
    public synchronized double rollingError() {
        return rollingError;
    }

    /** Standard deviation of recent prediction errors. */
    public synchronized double errorStdDev() {
        return rollingErrorStdDev;
    }

    public synchronized long observationCount() {
        return observationCount;
    }

    /**
     * Whether the agent is in a "surprised" state.
     * True when rolling error is significantly above its baseline.
     */
    public synchronized boolean isSurprised() {
        return rollingError > 0.5 && rollingErrorStdDev > 0.2;
    }

    private double computeStdDev() {
        if (recentErrors.size() < 2) return 0.0;
        double mean = recentErrors.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = recentErrors.stream()
                .mapToDouble(v -> (v - mean) * (v - mean))
                .average()
                .orElse(0.0);
        return Math.sqrt(variance);
    }
}
