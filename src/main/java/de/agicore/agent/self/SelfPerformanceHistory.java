package de.agicore.agent.self;

import java.util.*;
import java.util.logging.Logger;

/**
 * Tracks the agent's self-state over time for trend analysis.
 * <p>
 * The performance history enables:
 * <ul>
 *   <li>Detecting performance degradation (declining success rate)</li>
 *   <li>Identifying peak performance periods</li>
 *   <li>Comparing current state against historical baselines</li>
 * </ul>
 */
public class SelfPerformanceHistory {

    private static final Logger LOG = Logger.getLogger(SelfPerformanceHistory.class.getName());
    private static final int MAX_ENTRIES = 2_000;

    private final List<SelfState> history = new ArrayList<>();

    /** Record a new self-state snapshot. */
    public synchronized void record(SelfState state) {
        history.add(state);
        if (history.size() > MAX_ENTRIES) {
            history.removeFirst();
        }
    }

    /** Most recent snapshot. */
    public synchronized Optional<SelfState> latest() {
        return history.isEmpty() ? Optional.empty() : Optional.of(history.getLast());
    }

    /** All recorded snapshots (most recent last). */
    public synchronized List<SelfState> all() {
        return List.copyOf(history);
    }

    /** Average self-health over the last {@code window} snapshots. */
    public synchronized double averageHealth(int window) {
        if (history.isEmpty()) return 0.5;
        int w = Math.min(window, history.size());
        return history.subList(history.size() - w, history.size()).stream()
                .mapToDouble(SelfState::selfHealth)
                .average()
                .orElse(0.5);
    }

    /** Trend direction: positive = improving, negative = degrading. */
    public synchronized double healthTrend(int window) {
        if (history.size() < 2) return 0.0;
        int w = Math.min(window, history.size());
        double first = history.get(history.size() - w).selfHealth();
        double last = history.getLast().selfHealth();
        return last - first;
    }

    /**
     * Whether the agent has experienced a significant performance drop.
     * True when health trend over last 20 snapshots is declining by >10%.
     */
    public synchronized boolean hasDegraded() {
        return healthTrend(20) < -0.1 && history.size() >= 20;
    }

    public synchronized int size() { return history.size(); }
}
