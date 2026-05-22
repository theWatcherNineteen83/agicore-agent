package de.agicore.agent.memory;

import java.time.Instant;
import java.util.UUID;

/**
 * A single interaction the agent experienced.
 * <p>
 * Each experience captures what goal was pursued, what action was taken,
 * the expected outcome (if the planner provided one), the actual result,
 * and a derived prediction error. Salience determines how strongly this
 * experience is retained in long-term memory.
 * <p>
 * Immutable record. Mutations go through factory methods.
 *
 * @param id              unique identifier
 * @param timestamp       when the experience occurred
 * @param goalDescription which goal was active (or {@code "idle"})
 * @param actionName      name of the executed action
 * @param success         whether the action succeeded
 * @param body            action output (truncated)
 * @param predictionError absolute difference between expected and actual reward (0.0–1.0)
 * @param salience        importance score (0.0–1.0), used for memory consolidation
 * @param vector          feature vector for similarity retrieval (placeholder: raw data)
 */
public record Experience(
        UUID id,
        Instant timestamp,
        String goalDescription,
        String actionName,
        boolean success,
        String body,
        double predictionError,
        double salience,
        double[] vector) {

    /**
     * Create an experience from an action result.
     *
     * @param goalDescription  active goal or "idle"
     * @param actionName       which action ran
     * @param success          outcome
     * @param body             result body
     * @param predictionError  how wrong the planner was (0.0 = perfect)
     * @param vector           feature vector (may be empty but not null)
     */
    public Experience(String goalDescription, String actionName, boolean success,
                      String body, double predictionError, double[] vector) {
        this(UUID.randomUUID(), Instant.now(), goalDescription, actionName,
                success, body != null ? body : "",
                clamp(predictionError, 0.0, 1.0),
                computeSalience(predictionError, success),
                vector != null ? vector.clone() : new double[0]);
    }

    /**
     * Salience formula: prediction error drives salience.
     * Surprising outcomes (high prediction error) are remembered more strongly.
     * Success also boosts salience slightly.
     */
    private static double computeSalience(double predictionError, boolean success) {
        double base = predictionError * 0.7 + (success ? 0.3 : 0.1);
        return clamp(base, 0.0, 1.0);
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    @Override
    public String toString() {
        return "Exp[%s %s %s err=%.2f sal=%.2f]".formatted(
                timestamp, actionName, success ? "✓" : "✗", predictionError, salience);
    }
}
