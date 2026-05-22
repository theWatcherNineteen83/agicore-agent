package de.agicore.agent.workspace;

import java.util.*;
import java.util.logging.Logger;

/**
 * The limited-capacity attention buffer.
 * <p>
 * Holds at most {@code capacity} items — the agent's "conscious content."
 * New items compete for entry; lower-scoring items are evicted.
 * This embodies the attention bottleneck: only a small subset of all
 * available information can be consciously processed at any moment.
 * <p>
 * Capacity defaults to 7 (Miller's Law: 7±2 chunks).
 */
public class AttentionBuffer {

    private static final Logger LOG = Logger.getLogger(AttentionBuffer.class.getName());
    private static final int DEFAULT_CAPACITY = 7;

    private final int capacity;
    private final NavigableSet<ContentItem> buffer;

    public AttentionBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public AttentionBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer = new TreeSet<>(
                Comparator.comparingDouble(ContentItem::attentionScore).reversed()
                        .thenComparing(ContentItem::id));
    }

    /**
     * Try to place a content item into the attention buffer.
     * If the buffer is full, the new item competes against the
     * lowest-scoring occupant. If the new item scores higher, it replaces it.
     *
     * @return true if the item was accepted into the buffer
     */
    public synchronized boolean offer(ContentItem item) {
        if (buffer.size() < capacity) {
            buffer.add(item);
            LOG.fine(() -> "Attention: accepted " + item.summary() + " (buffer " + buffer.size() + "/" + capacity + ")");
            return true;
        }

        // Compete: find the lowest-scoring occupant
        ContentItem weakest = buffer.last();
        if (item.attentionScore() > weakest.attentionScore()) {
            buffer.remove(weakest);
            buffer.add(item);
            LOG.fine(() -> "Attention: replaced '" + weakest.summary()
                    + "' (score=" + String.format("%.2f", weakest.attentionScore())
                    + ") with '" + item.summary()
                    + "' (score=" + String.format("%.2f", item.attentionScore()) + ")");
            return true;
        }

        LOG.finest(() -> "Attention: rejected " + item.summary()
                + " (score=" + String.format("%.2f", item.attentionScore()) + ")");
        return false;
    }

    /**
     * Get the current attention contents (highest score first).
     * This is the "conscious broadcast" — what the agent is aware of right now.
     */
    public synchronized List<ContentItem> contents() {
        return List.copyOf(buffer);
    }

    /** The single highest-scoring item (the "focus of attention"). */
    public synchronized Optional<ContentItem> focus() {
        return buffer.isEmpty() ? Optional.empty() : Optional.of(buffer.first());
    }

    /** Clear all attention (e.g. after a tick). */
    public synchronized void clear() {
        buffer.clear();
    }

    /** Age items: reduce novelty over time (attention decays). */
    public synchronized void decay(double factor) {
        // Attention buffer is transient — items naturally expire between ticks.
        // For now, clearing is sufficient. In future: gradual decay.
        if (!buffer.isEmpty()) {
            buffer.clear();
        }
    }

    public synchronized int size() { return buffer.size(); }
    public int capacity() { return capacity; }
}
