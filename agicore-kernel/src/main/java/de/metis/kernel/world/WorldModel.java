package de.metis.kernel.world;

import de.metis.kernel.workspace.ContentItem;
import de.metis.kernel.persistence.KnowledgeStore;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The agent's unified world model — a dynamic belief network.
 * <p>
 * <b>What it stores:</b>
 * <ul>
 *   <li>Beliefs about external entities (servers, APIs, sensors)</li>
 *   <li>Beliefs about its own capabilities (which actions work)</li>
 *   <li>Beliefs about causal relationships (if X then Y)</li>
 * </ul>
 * <p>
 * <b>Belief revision:</b> beliefs are strengthened by confirming evidence
 * and weakened by contradictions. Low-confidence beliefs (<0.2) are
 * candidates for removal.
 * <p>
 * <b>Content generation:</b> the world model produces content items for
 * the Global Workspace, competing with memory, goals, and self-model.
 */
public class WorldModel {

    private static final Logger LOG = Logger.getLogger(WorldModel.class.getName());

    private final Map<String, Belief> beliefs = new ConcurrentHashMap<>();
    private KnowledgeStore knowledgeStore = null; // optional persistence

    /** Enable SQLite persistence for beliefs. */
    public void setKnowledgeStore(KnowledgeStore store) {
        this.knowledgeStore = store;
    }

    /** Load beliefs from persistence (called after setKnowledgeStore). */
    public void loadFromStore() {
        if (knowledgeStore == null) return;
        List<Belief> stored = knowledgeStore.loadBeliefs();
        for (Belief b : stored) {
            beliefs.put(b.statement(), b);
        }
        LOG.info("Loaded " + stored.size() + " beliefs from KnowledgeStore");
    }
    // Key = statement (simple dedup — future: embedding-based)

    /**
     * Add or update a belief. If the statement already exists,
     * the existing belief is reinforced or weakened based on
     * whether the new belief agrees.
     */
    public Belief update(String statement, double confidence, String source, boolean agrees) {
        Belief existing = beliefs.get(statement);
        if (existing != null) {
            if (agrees) {
                Belief strengthened = existing.reinforce();
                beliefs.put(statement, strengthened);
                if (knowledgeStore != null) knowledgeStore.saveBelief(strengthened);
                LOG.fine(() -> "Reinforced: " + strengthened);
                return strengthened;
            } else {
                Belief weakened = existing.weaken();
                if (weakened.confidence() < 0.15) {
                    beliefs.remove(statement);
                    LOG.fine(() -> "Removed weak belief: " + statement);
                } else {
                    beliefs.put(statement, weakened);
                    if (knowledgeStore != null) knowledgeStore.saveBelief(weakened);
                    LOG.fine(() -> "Weakened: " + weakened);
                }
                return weakened;
            }
        }

        Belief belief = new Belief(statement, confidence, source);
        beliefs.put(statement, belief);
        if (knowledgeStore != null) knowledgeStore.saveBelief(belief);
        LOG.fine(() -> "New belief: " + belief);
        return belief;
    }

    /**
     * Record an observation — updates the world model based on
     * an action result.
     */
    public void observe(String statement, boolean success) {
        update(statement, success ? 0.8 : 0.2, "observation", success);
    }

    /**
     * Query beliefs relevant to a goal or context string.
     * Simple substring match (Phase 3); Phase 4+ would use embeddings.
     */
    public List<Belief> query(String context, int maxResults) {
        String lower = context.toLowerCase();
        return beliefs.values().stream()
                .filter(b -> b.statement().toLowerCase().contains(lower))
                .sorted(Comparator.comparingDouble(Belief::confidence).reversed())
                .limit(maxResults)
                .toList();
    }

    /**
     * Generate content items for the Global Workspace.
     * High-confidence, recently-updated beliefs are more salient.
     */
    public List<ContentItem> generateWorldContent() {
        List<ContentItem> items = new ArrayList<>();
        for (Belief b : beliefs.values()) {
            // Only beliefs with decent evidence compete
            if (b.evidence() < 2 && b.confidence() < 0.5) continue;

            double novelty = b.evidence() <= 3 ? 0.6 : 0.1;
            double relevance = b.confidence();
            items.add(new ContentItem("world",
                    b.statement(), b.confidence(), novelty, relevance,
                    "belief: " + b));
        }
        return items;
    }

    /**
     * How many beliefs does the agent hold?
     */
    public int beliefCount() {
        return beliefs.size();
    }

    /**
     * Average confidence across all beliefs.
     */
    public double averageConfidence() {
        return beliefs.values().stream()
                .mapToDouble(Belief::confidence)
                .average()
                .orElse(0.0);
    }

    /** All beliefs, unmodifiable. */
    public Collection<Belief> all() {
        return List.copyOf(beliefs.values());
    }

    /**
     * The agent's current "world picture" — top-N highest-confidence beliefs.
     */
    public List<Belief> worldPicture(int topN) {
        return beliefs.values().stream()
                .sorted(Comparator.comparingDouble(Belief::confidence).reversed())
                .limit(topN)
                .toList();
    }
}
