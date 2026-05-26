package de.metis.kernel.world;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Causal reasoning layer for Metis's WorldModel.
 * <p>
 * Extends correlational Beliefs with cause-effect relationships.
 * Each link: (Action, Condition) → Effect with observed confidence.
 * <p>
 * Based on Pearl's Structural Causal Model, simplified for AGI.
 */
public class CausalModel {

    private static final Logger LOG = Logger.getLogger(CausalModel.class.getName());

    private final Map<String, CausalLink> links = new ConcurrentHashMap<>();

    /**
     * Record a cause-effect observation.
     *
     * @param cause     action or event (e.g. "action:shell")
     * @param condition context (e.g. "goal:system-check")
     * @param effect    outcome (e.g. "success")
     * @param success   whether the expected effect occurred
     */
    public void observe(String cause, String condition, String effect, boolean success) {
        String key = cause + "|" + condition + "→" + effect;
        links.computeIfAbsent(key, k -> new CausalLink(cause, condition, effect))
             .record(success);
    }

    /** Predict: "If I do X under condition Y, what happens?" */
    public List<Prediction> predict(String cause, String condition, int max) {
        List<Prediction> results = links.values().stream()
                .filter(l -> l.cause.equals(cause) 
                        && (condition == null || l.condition.contains(condition)))
                .map(l -> new Prediction(l.effect, l.confidence(), l.total()))
                .sorted((a, b) -> Double.compare(b.confidence, a.confidence))
                .limit(max)
                .toList();

        if (results.isEmpty() && LOG.isLoggable(java.util.logging.Level.FINE)) {
            LOG.fine("CausalModel: no prediction for " + cause + "/" + condition);
        }
        return results;
    }

    /** Explain: "Why did X happen?" */
    public List<CausalLink> explain(String effect, int max) {
        return links.values().stream()
                .filter(l -> l.effect.equals(effect))
                .sorted((a, b) -> Integer.compare(b.total(), a.total()))
                .limit(max)
                .toList();
    }

    /** Find the most effective action for a desired outcome. */
    public String bestAction(String desiredEffect, String condition) {
        return links.values().stream()
                .filter(l -> l.effect.equals(desiredEffect)
                        && (condition == null || l.condition.contains(condition)))
                .max(Comparator.comparingDouble(CausalLink::confidence))
                .map(l -> l.cause)
                .orElse(null);
    }

    public int size() { return links.size(); }

    // ── Data structures ───────────────────────────────────────

    public record Prediction(String effect, double confidence, int evidence) {}

    public static class CausalLink {
        public final String cause, condition, effect;
        int successes, failures;
        final Instant firstSeen = Instant.now();
        Instant lastSeen = Instant.now();

        CausalLink(String cause, String condition, String effect) {
            this.cause = cause; this.condition = condition; this.effect = effect;
        }

        synchronized void record(boolean success) {
            if (success) successes++; else failures++;
            lastSeen = Instant.now();
        }

        public double confidence() {
            int t = successes + failures;
            return t == 0 ? 0 : (double) successes / t;
        }

        public int total() { return successes + failures; }
        public int successes() { return successes; }
        public int failures() { return failures; }
    }
}
