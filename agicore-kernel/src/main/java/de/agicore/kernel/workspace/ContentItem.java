package de.agicore.kernel.workspace;

import java.time.Instant;
import java.util.UUID;

/**
 * A piece of information competing for the agent's attention.
 * <p>
 * ContentItems are submitted by subsystems (memory, goals, self-model,
 * external sensors) and compete in the {@link GlobalWorkspace}. The winner
 * is "broadcast" — made available to all subsystems for the current tick.
 * <p>
 * This implements Baars' Global Workspace Theory: only content that wins
 * the competition reaches consciousness; everything else stays unconscious.
 *
 * @param id          unique identifier
 * @param source      which subsystem produced this (e.g. "memory", "goal", "self")
 * @param summary     short human-readable description
 * @param salience    how attention-worthy (0.0–1.0)
 * @param novelty     how new/surprising (0.0–1.0), drives exploration
 * @param relevance   how relevant to current goals (0.0–1.0)
 * @param content     structured payload (may be JSON or free text)
 * @param timestamp   when this item was created
 */
public record ContentItem(
        UUID id,
        String source,
        String summary,
        double salience,
        double novelty,
        double relevance,
        String content,
        Instant timestamp) {

    /**
     * Create a content item with a balanced attention score.
     * Default weights: salience 40%, novelty 30%, relevance 30%.
     */
    public ContentItem(String source, String summary, double salience,
                       double novelty, double relevance, String content) {
        this(UUID.randomUUID(), source, summary,
                clamp(salience), clamp(novelty), clamp(relevance),
                content, Instant.now());
    }

    /**
     * Composite attention score.
     * <pre>score = 0.4 × salience + 0.3 × novelty + 0.3 × relevance</pre>
     */
    public double attentionScore() {
        return 0.4 * salience + 0.3 * novelty + 0.3 * relevance;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    @Override
    public String toString() {
        return String.format("Content[%s] %s (score=%.2f s=%.2f n=%.2f r=%.2f)",
                source, summary, attentionScore(), salience, novelty, relevance);
    }
}
