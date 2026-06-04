package de.metis.modules.evolution;

import java.util.*;
import java.util.logging.Logger;

/**
 * Phase 12b — Analysiert Metis-Performance-Metriken und erzeugt
 * Feature-Vorschlaege fuer autonome Verbesserung.
 *
 * <p>Der GapAnalyzer liest Metriken aus {@code /api/status} und
 * vergleicht sie mit Schwellwerten. Bei Unterschreitung wird ein
 * Feature-Vorschlag als Goal formuliert, den die FeatureGen-Pipeline
 * umsetzen kann (CodeGen → Compile → Eval → Deploy).
 */
public class GapAnalyzer {

    private static final Logger LOG = Logger.getLogger(GapAnalyzer.class.getName());

    /** Metrik-Schwellwerte (unterschritten = Gap). */
    private static final double MIN_PLANNING_EFFICIENCY = 0.4;
    private static final double MIN_SUCCESS_RATE = 0.5;
    private static final double MIN_CONFIDENCE = 0.3;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final int MIN_BELIEF_GROWTH_PER_HOUR = 10;

    private final List<String> recentSuggestions = new ArrayList<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private static final long COOLDOWN_MS = 3600_000; // 1h zwischen gleichen Vorschlae

    /**
     * Analysiert einen Metrik-Snapshot und gibt Feature-Vorschlaege zurueck.
     *
     * @return Liste von Feature-Goals (leer wenn nichts zu tun)
     */
    public List<FeatureProposal> analyze(Map<String, Object> metrics) {
        List<FeatureProposal> proposals = new ArrayList<>();

        if (metrics == null || metrics.isEmpty()) return proposals;

        // 1. Planning Efficiency
        double planningEff = getDouble(metrics, "planningEfficiency");
        if (planningEff < MIN_PLANNING_EFFICIENCY) {
            proposals.add(new FeatureProposal(
                    "improve_planning_efficiency",
                    "Planning efficiency is %.0f%% (threshold: %.0f%%)"
                            .formatted(planningEff * 100, MIN_PLANNING_EFFICIENCY * 100),
                    "Optimize planning prompt or reduce LLM fallbacks",
                    "modules/planner/OllamaPlanner.java", 50));
        }

        // 2. Success Rate
        double successRate = getDouble(metrics, "successRate");
        if (successRate < MIN_SUCCESS_RATE) {
            proposals.add(new FeatureProposal(
                    "improve_goal_success_rate",
                    "Goal success rate is %.0f%% (threshold: %.0f%%)"
                            .formatted(successRate * 100, MIN_SUCCESS_RATE * 100),
                    "Add retry logic or improve goal selection",
                    "kernel/goal/GoalManager.java", 60));
        }

        // 3. Confidence
        double confidence = getDouble(metrics, "confidence");
        if (confidence < MIN_CONFIDENCE) {
            proposals.add(new FeatureProposal(
                    "increase_agent_confidence",
                    "Agent confidence is %.2f (threshold: %.2f)"
                            .formatted(confidence, MIN_CONFIDENCE),
                    "Accumulate more evidence or improve self-assessment",
                    "kernel/meta/MetaCognition.java", 40));
        }

        // 4. Consecutive failures (from status or bugTracker)
        var bugTracker = getMap(metrics, "bugTracker");
        if (bugTracker != null && bugTracker.containsKey("bugCount")) {
            int bugs = ((Number) bugTracker.get("bugCount")).intValue();
            if (bugs > MAX_CONSECUTIVE_FAILURES) {
                proposals.add(new FeatureProposal(
                        "reduce_runtime_errors",
                        "%d runtime errors detected (threshold: %d)"
                                .formatted(bugs, MAX_CONSECUTIVE_FAILURES),
                        "Add null checks or improve error handling",
                        "kernel/self/BugTracker.java", 70));
            }
        }

        // 5. Growth rate (belief count change)
        // (requires snapshot comparison - placeholder for now)
        int beliefCount = getInt(metrics, "beliefCount");
        if (beliefCount < 50) {
            proposals.add(new FeatureProposal(
                    "accelerate_knowledge_acquisition",
                    "Only %d beliefs (threshold: 50)".formatted(beliefCount),
                    "Increase Wikipedia learning rate or add more sources",
                    "modules/knowledge/WikipediaKnowledgeService.java", 30));
        }

        // Filter by cooldown and dedup
        List<FeatureProposal> filtered = proposals.stream()
                .filter(p -> !isOnCooldown(p.id()))
                .toList();

        for (var p : filtered) {
            cooldowns.put(p.id(), System.currentTimeMillis());
            recentSuggestions.add(p.id() + ": " + p.shortDescription());
            LOG.info("GapAnalyzer: proposal=" + p.id() + " prio=" + p.priority()
                    + " — " + p.shortDescription());
        }

        // Keep only last 50 suggestions
        while (recentSuggestions.size() > 50) recentSuggestions.removeFirst();
        if (!recentSuggestions.isEmpty() && filtered.isEmpty()) {
            LOG.fine("GapAnalyzer: all proposals are on cooldown");
        }

        return filtered;
    }

    public List<String> recentSuggestions() { return List.copyOf(recentSuggestions); }

    private boolean isOnCooldown(String id) {
        Long last = cooldowns.get(id);
        return last != null && (System.currentTimeMillis() - last) < COOLDOWN_MS;
    }

    private double getDouble(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        return 1.0; // default: no gap
    }

    private int getInt(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        return Integer.MAX_VALUE;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Map) return (Map<String, Object>) v;
        return null;
    }

    /**
     * Ein konkreter Feature-Vorschlag mit Prioritaet und Ziel-Datei.
     */
    public record FeatureProposal(
            String id,
            String shortDescription,
            String proposedFix,
            String targetFile,
            int priority  // 0-100, hoeher = wichtiger
    ) {}
}
