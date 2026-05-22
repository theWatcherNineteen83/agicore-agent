package de.agicore.kernel.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Persistent (in-memory, for Phase 1) storage of experiences with
 * vector-based retrieval, salience tracking, and decay.
 * <p>
 * <b>Decay:</b> each experience loses salience exponentially over time:
 * <pre>effective = salience × e<sup>−λ·age</sup></pre>
 * where λ is {@link #decayRate}. Entries whose effective salience falls
 * below {@link #evictionThreshold} are pruned during consolidation.
 * <p>
 * <b>Consolidation:</b> experiences with high prediction error are
 * "rehearsed" (salience boost); low-error experiences decay faster.
 * This mirrors the hippocampal consolidation hypothesis.
 * <p>
 * <b>Retrieval:</b> linear scan with cosine similarity. No ANN yet —
 * that comes in Phase 2 when the vector index is added.
 * <p>
 * Extension points:
 * <ul>
 *   <li>pluggable similarity function</li>
 *   <li>pluggable decay function</li>
 *   <li>vector index backend (Phase 2)</li>
 *   <li>persistence backend (Phase 2)</li>
 * </ul>
 */
public class LongTermMemory {

    private static final Logger LOG = Logger.getLogger(LongTermMemory.class.getName());

    /** Default decay constant λ (per hour). */
    private static final double DEFAULT_DECAY_RATE = 0.01;
    /** Entries below this effective salience are evicted. */
    private static final double DEFAULT_EVICTION_THRESHOLD = 0.01;

    private final double decayRate;
    private final double evictionThreshold;
    private final List<Experience> store = new ArrayList<>();

    public LongTermMemory() {
        this(DEFAULT_DECAY_RATE, DEFAULT_EVICTION_THRESHOLD);
    }

    public LongTermMemory(double decayRate, double evictionThreshold) {
        this.decayRate = decayRate;
        this.evictionThreshold = evictionThreshold;
    }

    /**
     * Store an experience.
     */
    public synchronized void store(Experience exp) {
        store.add(exp);
        LOG.fine(() -> "LTM stored: " + exp);
    }

    /**
     * Store all experiences from short-term memory that exceed a
     * salience threshold. This is called by {@link MemoryConsolidator}.
     */
    public synchronized void storeAll(Collection<Experience> experiences) {
        for (Experience exp : experiences) {
            store.add(exp);
        }
        LOG.fine(() -> "LTM batch-stored " + experiences.size() + " experiences");
    }

    /**
     * Retrieve the top-{@code k} experiences most similar to a query vector
     * (cosine similarity), considering effective salience after decay.
     *
     * @param queryVec query vector
     * @param k        max results
     * @return sorted by similarity (descending)
     */
    public synchronized List<Experience> query(double[] queryVec, int k) {
        if (queryVec == null || queryVec.length == 0 || store.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now();
        return store.stream()
                .map(exp -> new ScoredExperience(exp, effectiveSalience(exp, now),
                        cosineSimilarity(queryVec, exp.vector())))
                .filter(se -> se.effectiveSalience > evictionThreshold)
                .sorted(Comparator.comparingDouble(ScoredExperience::similarity).reversed())
                .limit(k)
                .map(ScoredExperience::experience)
                .toList();
    }

    /**
     * Retrieve experiences related to a specific goal, sorted by salience.
     */
    public synchronized List<Experience> queryByGoal(String goalDescription, int k) {
        Instant now = Instant.now();
        return store.stream()
                .filter(exp -> exp.goalDescription().contains(goalDescription))
                .map(exp -> new ScoredExperience(exp, effectiveSalience(exp, now), 0.0))
                .filter(se -> se.effectiveSalience > evictionThreshold)
                .sorted(Comparator.comparingDouble(ScoredExperience::effectiveSalience).reversed())
                .limit(k)
                .map(ScoredExperience::experience)
                .toList();
    }

    /**
     * Consolidate: apply decay, boost high-error memories, evict stale entries.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>For each entry with predictionError &gt; 0.7: boost salience by 20%</li>
     *   <li>For each entry with predictionError &lt; 0.3: accelerate decay by 2×</li>
     *   <li>Remove entries whose effective salience is below threshold</li>
     * </ol>
     * This is the consolidation step — the agent "rehearses" surprising experiences.
     */
    public synchronized void consolidate() {
        Instant now = Instant.now();
        List<Experience> boosted = new ArrayList<>();
        List<Experience> kept = new ArrayList<>();
        int evicted = 0;

        for (Experience exp : store) {
            double effective = effectiveSalience(exp, now);

            // Boost surprising outcomes (high prediction error)
            if (exp.predictionError() > 0.7) {
                double boostedSalience = Math.min(1.0, exp.salience() * 1.2);
                boosted.add(new Experience(exp.id(), exp.timestamp(),
                        exp.goalDescription(), exp.actionName(), exp.success(),
                        exp.body(), exp.predictionError(), boostedSalience,
                        exp.vector()));
            } else if (exp.predictionError() < 0.3 && effective < evictionThreshold * 2) {
                // Low surprise + low salience → forget faster (already decaying)
                evicted++;
            } else if (effective >= evictionThreshold) {
                kept.add(exp);
            } else {
                evicted++;
            }
        }

        store.clear();
        store.addAll(boosted);
        store.addAll(kept);

        if (!boosted.isEmpty() || evicted > 0) {
            int evictedCount = evicted; // effectively final for lambda
            LOG.info(() -> "LTM consolidate: boosted=" + boosted.size()
                    + " kept=" + kept.size() + " evicted=" + evictedCount);
        }
    }

    /**
     * Effective salience after exponential decay.
     * <pre>effective = salience × e<sup>−λ × hours</sup></pre>
     */
    public double effectiveSalience(Experience exp, Instant now) {
        double hours = Duration.between(exp.timestamp(), now).toSeconds() / 3600.0;
        return exp.salience() * Math.exp(-decayRate * hours);
    }

    /**
     * Cosine similarity between two vectors.
     * Returns 0.0 if either vector is zero-length.
     */
    public static double cosineSimilarity(double[] a, double[] b) {
        if (a.length == 0 || b.length == 0) return 0.0;
        if (a.length != b.length) {
            // Pad or truncate: use the shorter length
            int len = Math.min(a.length, b.length);
            double dot = 0, normA = 0, normB = 0;
            for (int i = 0; i < len; i++) {
                dot += a[i] * b[i];
                normA += a[i] * a[i];
                normB += b[i] * b[i];
            }
            double denom = Math.sqrt(normA) * Math.sqrt(normB);
            return denom == 0 ? 0.0 : dot / denom;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }

    public synchronized int size() {
        return store.size();
    }

    /**
     * Evict the lowest-salience entries until size ≤ maxEntries.
     * Returns number evicted.
     */
    public synchronized int trimToSize(int maxEntries) {
        if (store.size() <= maxEntries) return 0;
        int toRemove = store.size() - maxEntries;
        // Sort by effective salience ascending, remove lowest
        Instant now = Instant.now();
        store.sort(Comparator.comparingDouble(exp -> effectiveSalience(exp, now)));
        for (int i = 0; i < toRemove; i++) {
            store.removeFirst();
        }
        LOG.info(() -> "LTM trimmed: removed " + toRemove + " entries (size now " + store.size() + ")");
        return toRemove;
    }

    public synchronized List<Experience> all() {
        return List.copyOf(store);
    }

    /** Internal pairing of experience with computed scores. */
    private record ScoredExperience(Experience experience,
                                     double effectiveSalience,
                                     double similarity) {}
}
