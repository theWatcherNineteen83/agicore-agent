package de.agicore.agent.workspace;

import java.util.*;
import java.util.logging.Logger;

/**
 * Collects content from all subsystems and selects which items enter
 * the limited-capacity attention buffer.
 * <p>
 * This is the "preconscious" stage — content from memory, goals,
 * self-model, and external sensors is evaluated and the most
 * attention-worthy items are forwarded to the {@link GlobalWorkspace}.
 * <p>
 * Selection criteria:
 * <ol>
 *   <li>Salience — how "loud" is this content?</li>
 *   <li>Novelty — is this surprising/unexpected?</li>
 *   <li>Relevance — does this help achieve current goals?</li>
 *   <li>Recency — was this just produced?</li>
 * </ol>
 */
public class CompetitiveSelector {

    private static final Logger LOG = Logger.getLogger(CompetitiveSelector.class.getName());

    private final AttentionBuffer buffer;

    /** Track recent broadcast sources to detect and break echo chambers. */
    private final Map<String, Integer> recentWinners = new java.util.LinkedHashMap<>();
    private static final int ECHO_WINDOW = 5;
    private static final int ECHO_THRESHOLD = 4; // >4 of last 5 = echo chamber source
    private static final double ECHO_PENALTY = 0.3; // reduce score to 30%

    public CompetitiveSelector(AttentionBuffer buffer) {
        this.buffer = buffer;
    }

    /**
     * Run the competition: feed all items to the attention buffer.
     * Higher-scoring items displace lower-scoring ones.
     *
     * @param items all content competing for attention
     * @return the items that won attention (the broadcast set)
     */
    public List<ContentItem> compete(Collection<ContentItem> items) {
        buffer.clear();

        // Anti-echo-chamber: penalize sources that dominate recent broadcasts
        String echoSource = findEchoChamberSource();

        items.stream()
                .map(item -> {
                    // If this source is in echo chamber, reduce its score
                    if (echoSource != null && echoSource.equals(item.source())) {
                        double penalizedScore = item.attentionScore() * ECHO_PENALTY;
                        // We can't modify the record, so we just sort lower
                        return new java.util.AbstractMap.SimpleEntry<>(item, penalizedScore);
                    }
                    return new java.util.AbstractMap.SimpleEntry<>(item, item.attentionScore());
                })
                .sorted(Map.Entry.<ContentItem, Double>comparingByValue().reversed())
                .forEach(entry -> buffer.offer(entry.getKey()));

        // Track winners for echo-chamber detection
        List<ContentItem> winners = buffer.contents();
        // Count unique sources in this broadcast (not per-item)
        java.util.Set<String> winningSources = new java.util.HashSet<>();
        for (ContentItem w : winners) {
            winningSources.add(w.source());
        }
        for (String src : winningSources) {
            recentWinners.merge(src, 1, Integer::sum);
        }
        // Keep only last ECHO_WINDOW broadcasts
        int totalEntries = recentWinners.values().stream().mapToInt(Integer::intValue).sum();
        while (totalEntries > ECHO_WINDOW * 2) {
            var firstKey = recentWinners.keySet().iterator().next();
            int removed = recentWinners.remove(firstKey);
            totalEntries -= removed;
        }

        LOG.fine(() -> "Competition: " + items.size() + " candidates → "
                + winners.size() + " winners" + (echoSource != null ? " [anti-echo: -" + echoSource + "]" : ""));
        return winners;
    }

    /** Find a source that has won >80% of recent broadcasts (echo chamber). */
    private String findEchoChamberSource() {
        int total = recentWinners.values().stream().mapToInt(Integer::intValue).sum();
        if (total < ECHO_WINDOW) return null;

        for (var entry : recentWinners.entrySet()) {
            if (entry.getValue() >= ECHO_THRESHOLD) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Convenience: compete with items from multiple sources,
     * tagged automatically.
     */
    public List<ContentItem> competeFromSources(Map<String, List<String>> sourceContents,
                                                 Map<String, Double> sourceSalience) {
        List<ContentItem> items = new ArrayList<>();
        for (var entry : sourceContents.entrySet()) {
            String source = entry.getKey();
            double salience = sourceSalience.getOrDefault(source, 0.5);
            for (String content : entry.getValue()) {
                items.add(new ContentItem(source, content, salience,
                        estimateNovelty(content), salience, content));
            }
        }
        return compete(items);
    }

    /** Rough novelty estimation based on content length (placeholder heuristic). */
    private double estimateNovelty(String content) {
        // Longer content tends to be more detailed → potentially more novel
        return Math.min(1.0, content.length() / 500.0);
    }

    public AttentionBuffer buffer() { return buffer; }
}
