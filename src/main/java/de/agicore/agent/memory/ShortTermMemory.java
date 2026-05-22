package de.agicore.agent.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Fixed-capacity ring buffer holding the most recent {@link Experience}s.
 * <p>
 * When capacity is reached, the oldest entry is silently evicted.
 * This is the agent's working memory — fast, limited, volatile.
 * <p>
 * Design decision: a plain synchronized list (no queue) because we need
 * indexed random access for context window construction in Phase 2.
 * Phase 1 has no concurrency requirement, but synchronisation is cheap.
 */
public class ShortTermMemory {

    private static final Logger LOG = Logger.getLogger(ShortTermMemory.class.getName());

    /** Sensible default: roughly 100 recent interactions. */
    public static final int DEFAULT_CAPACITY = 100;

    private final int capacity;
    private final List<Experience> buffer;

    public ShortTermMemory() {
        this(DEFAULT_CAPACITY);
    }

    public ShortTermMemory(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        this.buffer = new ArrayList<>(capacity);
    }

    /**
     * Append an experience. Evicts oldest if full.
     */
    public synchronized void add(Experience exp) {
        if (buffer.size() >= capacity) {
            Experience evicted = buffer.removeFirst();
            LOG.finest(() -> "STM evicted: " + evicted);
        }
        buffer.add(exp);
        LOG.finest(() -> "STM added: " + exp);
    }

    /**
     * Return the {@code n} most recent experiences (0 &lt; n ≤ size).
     * For a context window: {@code recent(10)} gives the last 10.
     */
    public synchronized List<Experience> recent(int n) {
        if (n <= 0) return List.of();
        int from = Math.max(0, buffer.size() - n);
        return List.copyOf(buffer.subList(from, buffer.size()));
    }

    /** All entries, most recent last. */
    public synchronized List<Experience> all() {
        return List.copyOf(buffer);
    }

    public synchronized int size() {
        return buffer.size();
    }

    public synchronized boolean isEmpty() {
        return buffer.isEmpty();
    }

    public int capacity() {
        return capacity;
    }
}
