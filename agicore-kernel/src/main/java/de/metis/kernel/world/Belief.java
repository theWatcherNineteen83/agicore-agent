package de.metis.kernel.world;

import java.time.Instant;
import java.util.UUID;

/**
 * A single belief the agent holds about the world.
 * <p>
 * Beliefs have confidence (how sure we are), source (where we learned it),
 * and evidence strength. Low-confidence beliefs are candidates for revision.
 *
 * @param id         unique identifier
 * @param statement  the belief content (e.g. "miniedi is reachable")
 * @param confidence how certain (0.0–1.0)
 * @param source     where this belief came from ("observation", "inference", "user")
 * @param evidence   number of confirming observations
 * @param createdAt  when this belief was formed
 * @param updatedAt  last time evidence was added
 */
public record Belief(
        UUID id,
        String statement,
        double confidence,
        String source,
        int evidence,
        Instant createdAt,
        Instant updatedAt) {

    public Belief(String statement, double confidence, String source) {
        this(UUID.randomUUID(), statement, clamp(confidence), source,
                1, Instant.now(), Instant.now());
    }

    /** Strengthen this belief with new confirming evidence. */
    public Belief reinforce() {
        double newConf = Math.min(1.0, confidence + 0.1);
        return new Belief(id, statement, newConf, source,
                evidence + 1, createdAt, Instant.now());
    }

    /** Weaken this belief (contradicting evidence). */
    public Belief weaken() {
        double newConf = Math.max(0.0, confidence - 0.15);
        return new Belief(id, statement, newConf, source,
                evidence, createdAt, Instant.now());
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    @Override
    public String toString() {
        return String.format("Belief[%s conf=%.2f src=%s ev=%d]",
                statement, confidence, source, evidence);
    }
}
