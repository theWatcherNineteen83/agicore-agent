package de.agicore.modules.planner;

import de.agicore.kernel.action.ActionResult;
import de.agicore.kernel.goal.Goal;
import de.agicore.kernel.memory.Experience;
import de.agicore.kernel.meta.MetaCognition;
import de.agicore.kernel.planner.Planner;
import de.agicore.kernel.workspace.ContentItem;
import de.agicore.kernel.world.Belief;
import de.agicore.kernel.world.WorldModel;

import de.agicore.modules.evolution.ModelRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * LLM-powered planner — uses Ollama for reasoning instead of keyword matching.
 * <p>
 * Replaces {@link StubPlanner} as the primary planning engine. Calls Ollama
 * on miniedi with a structured context prompt (goal, history, world model,
 * workspace attention, meta-cognition). Parses the JSON response to extract
 * the chosen action, reasoning, and confidence.
 * <p>
 * <b>Fallback chain:</b>
 * <ol>
 *   <li>Ollama LLM reasoning (primary)</li>
 *   <li>Learned action-goal mapping (secondary, from past successes)</li>
 *   <li>Keyword-based heuristic (tertiary, deterministic)</li>
 * </ol>
 * <p>
 * <b>Evolvability:</b> implements {@link Planner} (which extends
 * {@link de.agicore.kernel.planner.EvolvableModule}). The prompt template,
 * model selection, context construction, and fallback heuristics can all
 * be mutated by the EvolutionManager — the LLM mutates its own planner.
 */
public class OllamaPlanner implements Planner {

    private static final Logger LOG = Logger.getLogger(OllamaPlanner.class.getName());

    // ── Ollama configuration ──────────────────────────────────
    private String ollamaUrl;
    private Supplier<String> modelProvider;  // lazy: resolves from ModelRegistry or fixed string
    private Duration timeout;

    // ── EvolvableModule state ──────────────────────────────────
    private String version = "1.0.0";
    private double lastFitness = 0.0;

    // ── Learned action-goal mappings (fallback tier 2) ────────
    private final Map<String, Integer> planningSuccess = new ConcurrentHashMap<>();
    private final Map<String, Integer> planningAttempts = new ConcurrentHashMap<>();
    private static final int MIN_ATTEMPTS = 3;
    private static final double MIN_SUCCESS_RATE = 0.6;

    // ── World model reference (for context building) ──────────
    private WorldModel worldModel;

    // ── Available action names (cached for prompt context) ────
    private Set<String> availableActions = Set.of("shell", "http");

    // ── Statistics ─────────────────────────────────────────────
    private int llmCalls = 0;
    private int llmFailures = 0;
    private int fallbackUses = 0;
    private Instant lastLlmCall;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Create with defaults: miniedi Ollama, default model.
     * Use {@link #OllamaPlanner(String, ModelRegistry, Duration)} for auto-selection.
     */
    public OllamaPlanner() {
        this("http://192.168.22.204:11434/api/generate",
             "mistral-small3.1:24b",
             Duration.ofSeconds(60));
    }

    /**
     * Full configuration with explicit model name.
     *
     * @param ollamaUrl Ollama generate endpoint
     * @param model     model name (e.g. "mistral-small3.1:24b")
     * @param timeout   generation timeout
     */
    public OllamaPlanner(String ollamaUrl, String model, Duration timeout) {
        this.ollamaUrl = ollamaUrl;
        this.modelProvider = () -> model;
        this.timeout = timeout;
    }

    /**
     * Create with ModelRegistry for automatic model selection.
     * The registry is queried each planning call (cached internally by registry).
     */
    public OllamaPlanner(String ollamaUrl, ModelRegistry registry, Duration timeout) {
        this.ollamaUrl = ollamaUrl;
        this.modelProvider = registry::planningModel;
        this.timeout = timeout;
    }

    // ── Configuration setters (builder-friendly) ──────────────

    public OllamaPlanner withWorldModel(WorldModel wm) {
        this.worldModel = wm;
        return this;
    }

    public OllamaPlanner withAvailableActions(Set<String> actions) {
        this.availableActions = Set.copyOf(actions);
        return this;
    }

    // ── EvolvableModule ────────────────────────────────────────

    @Override public String moduleName() { return "ollama-planner"; }
    @Override public String version() { return version; }
    @Override public double lastFitness() { return lastFitness; }
    public void setVersion(String v) { this.version = v; }
    public void setLastFitness(double f) { this.lastFitness = f; }

    // ── Planner ────────────────────────────────────────────────

    @Override
    public List<String> plan(Goal goal, List<Experience> recentHistory,
                             List<ContentItem> broadcast, MetaCognition meta) {

        // ── Tier 1: LLM reasoning ──────────────────────────────
        String action = planViaOllama(goal, recentHistory, broadcast, meta);

        if (action != null && !action.isBlank() && availableActions.contains(action)) {
            LOG.fine(() -> "Ollama planned: " + action + " for goal: " + goal.description());
            return List.of(action);
        }

        // ── Tier 2: Learned mapping ────────────────────────────
        fallbackUses++;
        String learned = planViaLearnedMapping(goal, broadcast);
        if (learned != null) {
            LOG.fine(() -> "Learned fallback: " + learned + " for goal: " + goal.description());
            return List.of(learned);
        }

        // ── Tier 3: Keyword heuristic ──────────────────────────
        String keyword = planViaKeywords(goal, broadcast);
        if (keyword != null) {
            LOG.fine(() -> "Keyword fallback: " + keyword + " for goal: " + goal.description());
            return List.of(keyword);
        }

        return Collections.emptyList();
    }

    /**
     * Tier 1: Call Ollama with structured context and parse JSON response.
     */
    private String planViaOllama(Goal goal, List<Experience> recentHistory,
                                  List<ContentItem> broadcast, MetaCognition meta) {
        llmCalls++;
        lastLlmCall = Instant.now();

        String prompt = buildPlanningPrompt(goal, recentHistory, broadcast, meta);

        try {
            String jsonBody = String.format("""
                    {
                      "model": "%s",
                      "prompt": %s,
                      "stream": false,
                      "options": {
                        "temperature": 0.3,
                        "top_p": 0.9,
                        "num_predict": 256
                      }
                    }
                    """, resolveModel(), escapeJson(prompt));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                llmFailures++;
                LOG.warning("Ollama planner returned " + response.statusCode());
                return null;
            }

            String responseText = extractResponseField(response.body());
            if (responseText == null || responseText.isBlank()) {
                llmFailures++;
                return null;
            }

            // Parse JSON from response
            ParsedPlan plan = parsePlanResponse(responseText);
            if (plan != null && plan.action != null && !plan.action.isBlank()) {
                LOG.fine(() -> "LLM plan: action=" + plan.action
                        + " confidence=" + String.format("%.2f", plan.confidence)
                        + " reason=" + plan.reasoning);
                return plan.action.trim().toLowerCase();
            }

            llmFailures++;
            return null;

        } catch (Exception e) {
            llmFailures++;
            LOG.warning("Ollama planner call failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Build a focused planning prompt with all relevant context.
     * Structure: system instruction + goal + context + history + actions → JSON response.
     */
    private String buildPlanningPrompt(Goal goal, List<Experience> recentHistory,
                                        List<ContentItem> broadcast, MetaCognition meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an AGI action planner. Choose the best action for a goal.\n");
        sb.append("Respond with EXACTLY one JSON object: {\"action\":\"...\",\"reasoning\":\"...\",\"confidence\":0.XX}\n");
        sb.append("No markdown. No explanation outside the JSON. Just the JSON.\n\n");

        // Goal
        sb.append("GOAL: ").append(goal.description()).append("\n");
        sb.append("Category: ").append(goal.category()).append("\n");
        sb.append("Priority: ").append(goal.priority()).append("/100\n");
        sb.append("Expected reward: ").append(String.format("%.2f", goal.expectedReward())).append("\n\n");

        // Available actions
        sb.append("AVAILABLE ACTIONS: ").append(String.join(", ", availableActions)).append("\n\n");

        // Workspace attention (broadcast)
        if (!broadcast.isEmpty()) {
            sb.append("ATTENTION (what the agent is focused on):\n");
            for (ContentItem item : broadcast) {
                sb.append("- [").append(item.source()).append("] ")
                        .append(item.summary()).append("\n");
            }
            sb.append("\n");
        }

        // World model beliefs (most relevant to goal)
        if (worldModel != null) {
            List<Belief> relevant = worldModel.query(goal.description(), 5);
            if (!relevant.isEmpty()) {
                sb.append("WORLD KNOWLEDGE (relevant beliefs):\n");
                for (Belief b : relevant) {
                    sb.append("- ").append(b.statement())
                            .append(" (confidence: ").append(String.format("%.2f", b.confidence())).append(")\n");
                }
                sb.append("\n");
            }
        }

        // Recent history (last 5 experiences)
        if (!recentHistory.isEmpty()) {
            sb.append("RECENT HISTORY:\n");
            List<Experience> recent = recentHistory.size() > 5
                    ? recentHistory.subList(recentHistory.size() - 5, recentHistory.size())
                    : recentHistory;
            for (Experience exp : recent) {
                sb.append("- ").append(exp.actionName())
                        .append(exp.success() ? " ✓" : " ✗")
                        .append(" goal: ").append(exp.goalDescription())
                        .append(" (error: ").append(String.format("%.2f", exp.predictionError())).append(")\n");
            }
            sb.append("\n");
        }

        // Meta-cognitive state
        sb.append("AGENT STATE:\n");
        sb.append("Confidence: ").append(String.format("%.2f", meta.confidence())).append("\n");
        sb.append("Surprised: ").append(meta.isSurprised()).append("\n");
        sb.append("Rolling error: ").append(String.format("%.2f", meta.rollingError())).append("\n");
        sb.append("Observations: ").append(meta.observationCount()).append("\n\n");

        // Learned action-goal success rates (the agent's own experience)
        sb.append("LEARNED ACTION SUCCESS RATES (from past experience):\n");
        if (planningAttempts.isEmpty()) {
            sb.append("(no learned data yet)\n");
        } else {
            for (var entry : planningAttempts.entrySet()) {
                String key = entry.getKey();
                int att = entry.getValue();
                int succ = planningSuccess.getOrDefault(key, 0);
                double rate = att > 0 ? (double) succ / att : 0.0;
                if (att >= MIN_ATTEMPTS) {
                    sb.append("- ").append(key)
                            .append(": ").append(String.format("%.0f%%", rate * 100))
                            .append(" (").append(succ).append("/").append(att).append(")\n");
                }
            }
        }
        sb.append("\n");

        sb.append("Based on all context above, which SINGLE action should the agent execute NOW?\n");
        sb.append("Respond with ONLY the JSON object. No explanation. No markdown.\n");
        sb.append("{\"action\":\"");

        return sb.toString();
    }

    /**
     * Extract the "response" field from Ollama's JSON output.
     */
    private String extractResponseField(String json) {
        String searchKey = "\"response\":\"";
        int start = json.indexOf(searchKey);
        if (start < 0) {
            // Try "thinking" field (thinking models)
            searchKey = "\"thinking\":\"";
            start = json.indexOf(searchKey);
            if (start < 0) return null;
        }
        start += searchKey.length();

        StringBuilder val = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n' -> { val.append('\n'); i++; }
                    case 't' -> { val.append('\t'); i++; }
                    case 'r' -> { val.append('\r'); i++; }
                    case '"' -> { val.append('"'); i++; }
                    case '\\' -> { val.append('\\'); i++; }
                    default -> val.append(c);
                }
            } else if (c == '"') {
                break;
            } else {
                val.append(c);
            }
        }
        return val.toString();
    }

    /**
     * Parse the planning response. Handles various LLM output formats:
     * - Clean JSON: {"action":"shell","reasoning":"...","confidence":0.8}
     * - JSON with markdown fences: ```json ... ```
     * - Inline JSON within text
     */
    private ParsedPlan parsePlanResponse(String text) {
        if (text == null || text.isBlank()) return null;

        // Try to extract JSON from markdown fences
        String json = text;
        int fenceStart = json.indexOf("```json");
        if (fenceStart >= 0) {
            int codeStart = json.indexOf('\n', fenceStart) + 1;
            int codeEnd = json.indexOf("```", codeStart);
            if (codeEnd > codeStart) {
                json = json.substring(codeStart, codeEnd).strip();
            }
        } else {
            fenceStart = json.indexOf("```");
            if (fenceStart >= 0) {
                int codeStart = json.indexOf('\n', fenceStart) + 1;
                int codeEnd = json.indexOf("```", codeStart);
                if (codeEnd > codeStart) {
                    json = json.substring(codeStart, codeEnd).strip();
                }
            }
        }

        // Find JSON object boundaries
        int braceStart = json.indexOf('{');
        int braceEnd = json.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            json = json.substring(braceStart, braceEnd + 1);
        } else {
            return null;
        }

        try {
            String action = extractJsonStringField(json, "action");
            String reasoning = extractJsonStringField(json, "reasoning");
            double confidence = extractJsonDoubleField(json, "confidence");

            if (action != null) {
                return new ParsedPlan(action, reasoning != null ? reasoning : "", confidence);
            }
        } catch (Exception e) {
            LOG.fine("Failed to parse planning JSON: " + e.getMessage());
        }

        return null;
    }

    private String extractJsonStringField(String json, String fieldName) {
        String searchKey = "\"" + fieldName + "\":\"";
        int start = json.indexOf(searchKey);
        if (start < 0) return null;
        start += searchKey.length();

        StringBuilder val = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n' -> { val.append('\n'); i++; }
                    case 't' -> { val.append('\t'); i++; }
                    case 'r' -> { val.append('\r'); i++; }
                    case '"' -> { val.append('"'); i++; }
                    case '\\' -> { val.append('\\'); i++; }
                    default -> val.append(c);
                }
            } else if (c == '"') {
                break;
            } else {
                val.append(c);
            }
        }
        return val.toString();
    }

    private double extractJsonDoubleField(String json, String fieldName) {
        String searchKey = "\"" + fieldName + "\":";
        int start = json.indexOf(searchKey);
        if (start < 0) return 0.5;
        start += searchKey.length();

        StringBuilder num = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == '-' || c == 'e' || c == 'E') {
                num.append(c);
            } else {
                break;
            }
        }
        try {
            return Double.parseDouble(num.toString());
        } catch (NumberFormatException e) {
            return 0.5;
        }
    }

    /**
     * Tier 2: Use learned action-goal mappings from past successes.
     */
    private String planViaLearnedMapping(Goal goal, List<ContentItem> broadcast) {
        String keyword = extractKeyword(goal.description());

        // Check workspace-influenced mappings first
        for (ContentItem item : broadcast) {
            if ("world".equals(item.source()) && item.summary().contains("shell")) {
                if (goal.description().toLowerCase().contains("shell")
                        || goal.description().toLowerCase().contains("system")
                        || goal.description().toLowerCase().contains("command")) {
                    return "shell";
                }
            }
            if ("world".equals(item.source()) && item.summary().toLowerCase().contains("http")) {
                if (goal.description().toLowerCase().contains("http")
                        || goal.description().toLowerCase().contains("api")) {
                    return "http";
                }
            }
        }

        // Learned success-rate-based mapping
        for (String action : availableActions) {
            String key = keyword + ":" + action;
            int attempts = planningAttempts.getOrDefault(key, 0);
            int successes = planningSuccess.getOrDefault(key, 0);
            if (attempts >= MIN_ATTEMPTS
                    && (double) successes / attempts >= MIN_SUCCESS_RATE) {
                return action;
            }
        }

        // Partial keyword match fallback
        for (var entry : planningAttempts.entrySet()) {
            if (entry.getKey().startsWith(keyword + ":") && entry.getValue() > 0) {
                return entry.getKey().substring(keyword.length() + 1);
            }
        }

        return null;
    }

    /**
     * Tier 3: Pure keyword heuristic (always available).
     */
    private String planViaKeywords(Goal goal, List<ContentItem> broadcast) {
        String desc = goal.description().toLowerCase();

        for (ContentItem item : broadcast) {
            if ("world".equals(item.source()) && item.summary().contains("shell actions execute reliably")) {
                if (desc.contains("shell") || desc.contains("command") || desc.contains("system")) {
                    return "shell";
                }
            }
        }

        if (desc.contains("shell") || desc.contains("command") || desc.contains("system")) return "shell";
        if (desc.contains("http") || desc.contains("api") || desc.contains("request") || desc.contains("web")) return "http";

        return null;
    }

    // ── Learning hooks ─────────────────────────────────────────

    @Override
    public double expectedSuccess(Goal goal, String actionName) {
        // Check learned success rates first
        String keyword = extractKeyword(goal.description());
        String key = keyword + ":" + actionName;
        int attempts = planningAttempts.getOrDefault(key, 0);
        int successes = planningSuccess.getOrDefault(key, 0);
        if (attempts > 0) return (double) successes / attempts;
        return 0.5;
    }

    @Override
    public void learnFromOutcome(Goal goal, List<String> plan, ActionResult result) {
        if (plan.isEmpty() || result == null) return;
        String keyword = extractKeyword(goal.description());
        String action = plan.getFirst();
        String key = keyword + ":" + action;
        planningAttempts.merge(key, 1, Integer::sum);
        if (result.success()) planningSuccess.merge(key, 1, Integer::sum);
    }

    // ── Utility ────────────────────────────────────────────────

    private String extractKeyword(String desc) {
        String lower = desc.toLowerCase();
        String[] words = lower.split("\\s+");
        for (String w : words) {
            if (!w.matches("^(a|an|the|to|for|in|on|at|of|and|or|run|send|check|get|do|with|using|by|is|it|this|that)$"))
                return w;
        }
        return words.length > 0 ? words[0] : "unknown";
    }

    /** Resolve model name from provider (lazy, allows runtime model switching). */
    private String resolveModel() {
        return modelProvider != null ? modelProvider.get() : "mistral-small3.1:24b";
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // ── Accessors ───────────────────────────────────────────────

    /** Learned (goalKeyword:action) → success rate. */
    public Map<String, Double> learnedSuccessRates() {
        Map<String, Double> rates = new LinkedHashMap<>();
        for (String key : planningAttempts.keySet()) {
            int att = planningAttempts.get(key);
            int succ = planningSuccess.getOrDefault(key, 0);
            rates.put(key, att == 0 ? 0.0 : (double) succ / att);
        }
        return rates;
    }

    public int llmCalls() { return llmCalls; }
    public int llmFailures() { return llmFailures; }
    public int fallbackUses() { return fallbackUses; }
    public Instant lastLlmCall() { return lastLlmCall; }

    /**
     * How often the LLM was successfully used (as fraction of attempts).
     */
    public double llmSuccessRate() {
        return llmCalls == 0 ? 0.0 : (double) (llmCalls - llmFailures) / llmCalls;
    }

    // ── Parsed plan record ──────────────────────────────────────

    private record ParsedPlan(String action, String reasoning, double confidence) {}
}
