package de.agicore.agent.planner;

import de.agicore.agent.action.ActionResult;
import de.agicore.agent.goal.Goal;
import de.agicore.agent.memory.Experience;
import de.agicore.agent.meta.MetaCognition;
import de.agicore.agent.workspace.ContentItem;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Minimal planner stub for Phase 1.
 * <p>
 * No LLM integration yet — this is an explicitly allowed stub per the
 * Phase 1 specification. It returns a hardcoded single-action plan that
 * matches the goal description against known action names:
 * <ul>
 *   <li>Goal contains "shell" or "command" → {@code ["shell"]}</li>
 *   <li>Goal contains "http" or "api" or "request" → {@code ["http"]}</li>
 *   <li>Otherwise → empty plan (no action known)</li>
 * </ul>
 * <p>
 * Phase 2 will replace this with an Ollama-backed reasoning planner.
 */
public class StubPlanner implements Planner {

    private static final Logger LOG = Logger.getLogger(StubPlanner.class.getName());

    /**
     * Tracks which keyword→action mappings have been successful.
     * Key = "keyword:action", Value = success count.
     * This is the planner's self-improvement memory.
     */
    private final Map<String, Integer> planningSuccess = new ConcurrentHashMap<>();
    private final Map<String, Integer> planningAttempts = new ConcurrentHashMap<>();

    /** Minimum success rate to prefer a learned mapping over the default keyword match. */
    private static final double MIN_SUCCESS_RATE = 0.5;
    private static final int MIN_ATTEMPTS = 2;

    @Override
    public List<String> plan(Goal goal, List<Experience> recentHistory,
                             List<ContentItem> broadcast, MetaCognition meta) {
        String desc = goal.description().toLowerCase();

        // ── Fix #1: Workspace broadcast influences planning ────
        // If world-model content suggests a specific action type is reliable, use it
        for (ContentItem item : broadcast) {
            if ("world".equals(item.source()) && item.summary().contains("shell actions execute reliably")) {
                if (desc.contains("shell") || desc.contains("command") || desc.contains("system")) {
                    LOG.info(() -> "StubPlanner: broadcast-biased → shell (world model says reliable)");
                    return List.of("shell");
                }
            }
            if ("world".equals(item.source()) && item.summary().toLowerCase().contains("http")) {
                if (desc.contains("http") || desc.contains("api") || desc.contains("request")) {
                    LOG.info(() -> "StubPlanner: broadcast-biased → http");
                    return List.of("http");
                }
            }
        }

        // Check learned mappings first (self-improvement)
        String keyword = extractKeyword(desc);

        // Try learned action for this keyword with proven success
        for (String action : List.of("shell", "http")) {
            String key = keyword + ":" + action;
            int attempts = planningAttempts.getOrDefault(key, 0);
            int successes = planningSuccess.getOrDefault(key, 0);
            if (attempts >= MIN_ATTEMPTS && (double) successes / attempts >= MIN_SUCCESS_RATE) {
                LOG.info(() -> "StubPlanner: learned mapping '" + keyword + "' → '" + action
                        + "' (" + successes + "/" + attempts + ")");
                return List.of(action);
            }
        }

        // Fall back to keyword matching
        if (desc.contains("shell") || desc.contains("command")) {
            return List.of("shell");
        }
        if (desc.contains("http") || desc.contains("api") || desc.contains("request")) {
            return List.of("http");
        }

        // Last resort: try any action that has worked for this keyword before
        for (var entry : planningAttempts.entrySet()) {
            if (entry.getKey().startsWith(keyword + ":") && entry.getValue() > 0) {
                String action = entry.getKey().substring(keyword.length() + 1);
                double rate = (double) planningSuccess.getOrDefault(entry.getKey(), 0) / entry.getValue();
                LOG.info(() -> "StubPlanner: fallback '" + keyword + "' → '" + action
                        + "' (rate=" + String.format("%.0f%%", rate * 100) + ")");
                return List.of(action);
            }
        }

        LOG.warning(() -> "StubPlanner: no matching action for goal: " + goal.description());
        return Collections.emptyList();
    }

    /**
     * Fix #2: Expected success based on learned mapping history.
     * If we've tried this action for this keyword, return the historical success rate.
     * Otherwise return neutral 0.5.
     */
    @Override
    public double expectedSuccess(Goal goal, String actionName) {
        String keyword = extractKeyword(goal.description().toLowerCase());
        String key = keyword + ":" + actionName;
        int attempts = planningAttempts.getOrDefault(key, 0);
        int successes = planningSuccess.getOrDefault(key, 0);
        if (attempts > 0) {
            return (double) successes / attempts;
        }
        return 0.5; // unknown → maximum uncertainty
    }

    @Override
    public void learnFromOutcome(Goal goal, List<String> plan, ActionResult result) {
        if (plan.isEmpty() || result == null) return;

        String keyword = extractKeyword(goal.description().toLowerCase());
        String action = plan.getFirst();
        String key = keyword + ":" + action;

        planningAttempts.merge(key, 1, Integer::sum);
        if (result.success()) {
            planningSuccess.merge(key, 1, Integer::sum);
        }

        LOG.fine(() -> "Planner learned: '" + key + "' → "
                + (result.success() ? "success" : "failure")
                + " (" + planningSuccess.getOrDefault(key, 0) + "/"
                + planningAttempts.getOrDefault(key, 0) + ")");
    }

    /** Extract the first significant word from a goal description as a category key. */
    private String extractKeyword(String desc) {
        // Skip common words, pick the first meaningful noun/verb
        String[] words = desc.split("\\s+");
        for (String w : words) {
            if (!w.matches("^(a|an|the|to|for|in|on|at|of|and|or|run|send|check|get|do)$")) {
                return w;
            }
        }
        return words.length > 0 ? words[0] : "unknown";
    }

    /** Visible for testing: learned success rates. */
    public Map<String, Double> learnedSuccessRates() {
        Map<String, Double> rates = new LinkedHashMap<>();
        for (String key : planningAttempts.keySet()) {
            int attempts = planningAttempts.get(key);
            int successes = planningSuccess.getOrDefault(key, 0);
            rates.put(key, attempts == 0 ? 0.0 : (double) successes / attempts);
        }
        return rates;
    }
}
