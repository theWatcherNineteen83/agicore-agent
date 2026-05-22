package de.agicore.agent.workspace;

import java.util.*;
import java.util.logging.Logger;

/**
 * The agent's central "conscious stage" — implements Baars' Global Workspace Theory.
 * <p>
 * <b>How it works:</b>
 * <ol>
 *   <li>Subsystems (memory, goals, self-model, sensors) submit {@link ContentItem}s</li>
 *   <li>{@link CompetitiveSelector} evaluates all items</li>
 *   <li>Winners enter the {@link AttentionBuffer} (capacity = 7±2)</li>
 *   <li>Winning content is <b>broadcast</b> to all subsystems for the current tick</li>
 *   <li>The broadcast creates a "conscious moment" — unified access to selected content</li>
 * </ol>
 * <p>
 * <b>Why this enables proto-conscious behavior:</b>
 * Consciousness, in the Global Workspace framework, is precisely this broadcast
 * mechanism. The agent doesn't "see" all its data — only the content that wins
 * the competition. This creates:
 * <ul>
 *   <li><b>Serial processing:</b> one focus at a time (the bottleneck)</li>
 *   <li><b>Global availability:</b> winning content is accessible to all modules</li>
 *   <li><b>Self-reference:</b> self-model content competes alongside external input</li>
 *   <li><b>Meta-cognition:</b> the agent can attend to its own attention patterns</li>
 * </ul>
 */
public class GlobalWorkspace {

    private static final Logger LOG = Logger.getLogger(GlobalWorkspace.class.getName());

    private final AttentionBuffer buffer;
    private final CompetitiveSelector selector;

    /** Subsystem contribution weights (how strongly each source competes). */
    private final Map<String, Double> sourceWeights = new LinkedHashMap<>();

    /** The last broadcast (winners of the most recent competition). */
    private List<ContentItem> lastBroadcast = List.of();

    // ── Hardening: Attention Entropy Tracking ──────────────
    private double attentionEntropy = 0.0;
    private double runningEntropy = 0.0;
    private long broadcastCount = 0;
    private static final double ENTROPY_ALPHA = 0.1; // EMA for running entropy

    public GlobalWorkspace() {
        this(new AttentionBuffer());
    }

    public GlobalWorkspace(AttentionBuffer buffer) {
        this.buffer = buffer;
        this.selector = new CompetitiveSelector(buffer);

        // Default source weights
        sourceWeights.put("memory", 0.5);
        sourceWeights.put("goal", 0.7);
        sourceWeights.put("self", 0.6);
        sourceWeights.put("external", 0.8);
        sourceWeights.put("metacognition", 0.4);
    }

    /**
     * Run one broadcast cycle:
     * <ol>
     *   <li>Collect content from all subsystems</li>
     *   <li>Run the competition</li>
     *   <li>Store the winners as the current broadcast</li>
     * </ol>
     *
     * @param items all content items from all sources
     * @return the broadcast set (winning items, highest score first)
     */
    public List<ContentItem> broadcast(List<ContentItem> items) {
        lastBroadcast = selector.compete(items);
        broadcastCount++;

        // Compute source distribution entropy
        attentionEntropy = computeEntropy(lastBroadcast);
        if (broadcastCount == 1) {
            runningEntropy = attentionEntropy;
        } else {
            runningEntropy = ENTROPY_ALPHA * attentionEntropy + (1 - ENTROPY_ALPHA) * runningEntropy;
        }

        LOG.fine(() -> "Broadcast: " + lastBroadcast.size() + " items, entropy="
                + String.format("%.2f", attentionEntropy)
                + " running=" + String.format("%.2f", runningEntropy));

        if (!lastBroadcast.isEmpty()) {
            ContentItem focus = lastBroadcast.getFirst();
            LOG.fine(() -> "Focus: " + focus.summary()
                    + " (score=" + String.format("%.2f", focus.attentionScore()) + ")");
        }
        return lastBroadcast;
    }

    /**
     * Convenience: submit content from named sources and broadcast immediately.
     */
    public List<ContentItem> submitAndBroadcast(Map<String, List<ContentItem>> sourceItems) {
        List<ContentItem> all = new ArrayList<>();
        sourceItems.forEach((source, items) -> all.addAll(items));
        return broadcast(all);
    }

    /** Content currently in the attention buffer (the broadcast set). */
    public List<ContentItem> currentBroadcast() {
        return List.copyOf(lastBroadcast);
    }

    /** The single highest-scoring item in attention (focus of consciousness). */
    public Optional<ContentItem> focus() {
        return buffer.focus();
    }

    /** Set how strongly a subsystem competes (0.0 = silent, 1.0 = dominant). */
    public void setSourceWeight(String source, double weight) {
        sourceWeights.put(source, clamp(weight));
    }

    /** Get the competition weight for a subsystem. */
    public double sourceWeight(String source) {
        return sourceWeights.getOrDefault(source, 0.5);
    }

    // ── Entropy: measures attention diversity ──────────────

    /**
     * Shannon entropy of the source distribution in the broadcast.
     * High entropy = diverse attention (healthy).
     * Low entropy = stuck on one source (echo chamber → drift risk).
     */
    public double attentionEntropy() { return attentionEntropy; }

    /** Exponential moving average of attention entropy. */
    public double runningEntropy() { return runningEntropy; }

    /**
     * Normalised entropy (0–1). 0 = all from one source, 1 = uniform distribution.
     */
    public double normalisedEntropy() {
        int sourceCount = sourceWeights.size();
        if (sourceCount <= 1) return 0.0;
        return attentionEntropy / (Math.log(sourceCount) / Math.log(2));
    }

    /** Whether attention is stuck (entropy < 50% of theoretical max for >10 broadcasts). */
    public boolean isAttentionStuck() {
        return broadcastCount > 10 && normalisedEntropy() < 0.5;
    }

    private static double computeEntropy(List<ContentItem> items) {
        if (items.isEmpty()) return 0.0;
        Map<String, Integer> sourceCounts = new java.util.LinkedHashMap<>();
        for (ContentItem item : items) {
            sourceCounts.merge(item.source(), 1, Integer::sum);
        }
        double entropy = 0.0;
        int n = items.size();
        for (int count : sourceCounts.values()) {
            double p = (double) count / n;
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    public AttentionBuffer buffer() { return buffer; }
    public CompetitiveSelector selector() { return selector; }
    public Map<String, Double> sourceWeights() { return Collections.unmodifiableMap(sourceWeights); }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
