package de.metis.modules.planner;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;
import de.metis.kernel.goal.Goal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Prompt Chaining (Prompting-Kurz&Gut / Huyen Kap. 6): decomposes complex
 * multi-step goals into a chain of sub-goals, each building on the previous.
 * <p>
 * <b>Pattern:</b> Decompose → Execute → Aggregate
 * <ol>
 *   <li>Call Ollama to break a complex goal into sequential sub-goals</li>
 *   <li>Each sub-goal specifies: description, expected action, input context</li>
 *   <li>Results flow from step N into step N+1 as context</li>
 *   <li>Final step synthesizes the chain result</li>
 * </ol>
 * <p>
 * <b>Usage from Agent:</b>
 * <pre>{@code
 *   PromptChainingService chainer = new PromptChainingService(ollamaUrl, model);
 *   ChainResult result = chainer.decompose("Research Azul Prime and create report");
 *   for (ChainStep step : result.steps) {
 *       // execute step.action or plan step.description
 *   }
 * }</pre>
 *
 * @since Phase 5 (28.05.2026)
 */
public class PromptChainingService {

    private static final Logger LOG = Logger.getLogger(PromptChainingService.class.getName());

    private final String ollamaUrl;
    private final String model;
    private final Duration timeout;
    private final HttpClient http;

    // Statistics
    private int chainsCreated = 0;
    private int chainsCompleted = 0;
    private int totalSteps = 0;
    private int stepsExecuted = 0;

    public PromptChainingService(String ollamaUrl, String model, Duration timeout) {
        this.ollamaUrl = ollamaUrl;
        this.model = model;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public PromptChainingService(String ollamaUrl, String model) {
        this(ollamaUrl, model, Duration.ofSeconds(90));
    }

    // ── Public API ─────────────────────────────────────────────

    /**
     * Decompose a complex goal into a chain of sequential sub-goals.
     * Uses Ollama to reason about the best decomposition strategy.
     *
     * @param complexGoal the high-level goal description
     * @param availableActions names of actions the agent can use
     * @param context additional context (previous results, world state)
     * @return the decomposed chain with ordered steps
     */
    public ChainResult decompose(String complexGoal, Set<String> availableActions, String context) {
        chainsCreated++;
        String prompt = buildDecompositionPrompt(complexGoal, availableActions, context);

        try {
            String response = callOllama(prompt);
            if (response == null) {
                LOG.warning("PromptChaining: Ollama decomposition returned null, using single-step fallback");
                return singleStepFallback(complexGoal);
            }

            List<ChainStep> steps = parseChainResponse(response, complexGoal);
            if (steps.isEmpty()) {
                LOG.warning("PromptChaining: failed to parse chain, using single-step fallback");
                return singleStepFallback(complexGoal);
            }

            totalSteps += steps.size();
            LOG.info(() -> "PromptChaining: decomposed '" + truncate(complexGoal, 60)
                    + "' → " + steps.size() + " steps: "
                    + steps.stream().map(ChainStep::description).collect(Collectors.joining(" → ")));

            return new ChainResult(UUID.randomUUID(), complexGoal, steps, steps.size(), context);
        } catch (Exception e) {
            LOG.warning("PromptChaining: decompose failed: " + e.getMessage());
            return singleStepFallback(complexGoal);
        }
    }

    /**
     * Mark a step as executed with its result.
     * The result text will be used as context for subsequent steps.
     */
    public void recordStepResult(ChainResult chain, int stepIndex, String resultText, boolean success) {
        if (chain == null || stepIndex < 0 || stepIndex >= chain.steps.size()) return;
        ChainStep step = chain.steps.get(stepIndex);
        ChainStep updated = step.withResult(resultText, success);
        chain.steps.set(stepIndex, updated);
        stepsExecuted++;
        if (stepIndex == chain.steps.size() - 1) {
            chainsCompleted++;
        }
    }

    /**
     * Synthesize a final answer from all step results.
     * Calls Ollama to aggregate the chain into a coherent summary.
     */
    public String synthesize(ChainResult chain) {
        if (chain == null || chain.steps.isEmpty()) return "No chain to synthesize.";

        StringBuilder results = new StringBuilder();
        for (int i = 0; i < chain.steps.size(); i++) {
            ChainStep step = chain.steps.get(i);
            results.append("Step ").append(i + 1).append(": ").append(step.description).append("\n");
            results.append("Result: ").append(step.resultText != null ? step.resultText : "(not executed)").append("\n");
            results.append("Success: ").append(step.success).append("\n\n");
        }

        String prompt = "You are Metis, synthesizing results from a multi-step chain.\n\n"
                + "ORIGINAL GOAL: " + chain.complexGoal + "\n\n"
                + "CHAIN RESULTS:\n" + results + "\n"
                + "Synthesize ALL results into a single, concise answer. "
                + "Include key findings, data points, and conclusions. "
                + "If any step failed, note the gap. Be direct and informative.";

        try {
            String synthResponse = callOllamaSimple(prompt);
            return synthResponse != null ? synthResponse.strip()
                    : "Chain completed. " + stepsExecuted + "/" + chain.totalSteps + " steps executed.";
        } catch (Exception e) {
            return "Chain completed. " + stepsExecuted + "/" + chain.totalSteps
                    + " steps executed. (synthesis failed: " + e.getMessage() + ")";
        }
    }

    // ── Prompt construction ────────────────────────────────────

    private String buildDecompositionPrompt(String goal, Set<String> availableActions, String context) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are Metis, an AI agent that decomposes complex tasks into sequential steps.\n");
        sb.append("Break down the following goal into 2-5 ordered sub-goals. Each step builds on previous results.\n\n");
        sb.append("RULES:\n");
        sb.append("- Steps MUST be sequential: step N output → step N+1 input\n");
        sb.append("- Each step needs exactly ONE action from the available list\n");
        sb.append("- First step gathers data, middle steps process, last step summarizes/stores\n");
        sb.append("- Be specific: include URLs, file paths, search queries where relevant\n");
        sb.append("- Prefer specialized actions (webscrape, api-explore, filesystem-read) over generic shell/http\n\n");

        sb.append("ORIGINAL GOAL: ").append(goal).append("\n\n");

        sb.append("AVAILABLE ACTIONS: ").append(String.join(", ", availableActions)).append("\n");

        if (context != null && !context.isBlank()) {
            sb.append("ADDITIONAL CONTEXT: ").append(context).append("\n");
        }

        sb.append("\nRespond with ONLY this JSON (no markdown, no extra text):\n");
        sb.append("{\n");
        sb.append("  \"goal\": \"<original goal restated>\",\n");
        sb.append("  \"reasoning\": \"<why this decomposition strategy>\",\n");
        sb.append("  \"steps\": [\n");
        sb.append("    {\n");
        sb.append("      \"step\": 1,\n");
        sb.append("      \"description\": \"<concrete sub-goal, include specific URLs/queries/params>\",\n");
        sb.append("      \"action\": \"<action name from available list>\",\n");
        sb.append("      \"expected_output\": \"<what this step should produce>\"\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}");

        return sb.toString();
    }

    // ── Ollama communication ───────────────────────────────────

    private String callOllama(String prompt) {
        try {
            String jsonBody = String.format("""
                    {
                      "model": "%s",
                      "prompt": %s,
                      "stream": false,
                      "format": "json",
                      "options": {
                        "temperature": 0.3,
                        "top_p": 0.95,
                        "num_predict": 512,
                        "num_ctx": 4096
                      },
                      "keep_alive": "10m"
                    }
                    """, model, escapeJson(prompt));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.warning("PromptChaining: Ollama returned " + response.statusCode());
                return null;
            }

            return extractResponseField(response.body());
        } catch (Exception e) {
            LOG.warning("PromptChaining: Ollama call failed: " + e.getMessage());
            return null;
        }
    }

    /** Simple Ollama call without format:json (for synthesis). */
    private String callOllamaSimple(String prompt) {
        try {
            String jsonBody = String.format("""
                    {
                      "model": "%s",
                      "prompt": %s,
                      "stream": false,
                      "options": {
                        "temperature": 0.4,
                        "num_predict": 512,
                        "num_ctx": 4096
                      },
                      "keep_alive": "10m"
                    }
                    """, model, escapeJson(prompt));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? extractResponseField(response.body()) : null;
        } catch (Exception e) {
            LOG.warning("PromptChaining: simple Ollama call failed: " + e.getMessage());
            return null;
        }
    }

    // ── Response parsing ───────────────────────────────────────

    private List<ChainStep> parseChainResponse(String json, String goal) {
        try {
            // Extract the "steps" array
            int stepsIdx = json.indexOf("\"steps\":");
            if (stepsIdx < 0) return Collections.emptyList();

            int arrayStart = json.indexOf('[', stepsIdx);
            int arrayEnd = json.lastIndexOf(']');
            if (arrayStart < 0 || arrayEnd <= arrayStart) return Collections.emptyList();

            String stepsJson = json.substring(arrayStart + 1, arrayEnd);

            List<ChainStep> steps = new ArrayList<>();
            int depth = 0, objStart = -1;

            for (int i = 0; i < stepsJson.length(); i++) {
                char c = stepsJson.charAt(i);
                if (c == '{') {
                    if (depth == 0) objStart = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && objStart >= 0) {
                        String stepObj = stepsJson.substring(objStart, i + 1);
                        ChainStep step = parseSingleStep(stepObj);
                        if (step != null) steps.add(step);
                        objStart = -1;
                    }
                }
            }

            return steps;
        } catch (Exception e) {
            LOG.warning("PromptChaining: parse error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private ChainStep parseSingleStep(String json) {
        try {
            int stepNum = (int) extractDoubleField(json, "step");
            String desc = extractStringField(json, "description");
            String action = extractStringField(json, "action");
            String expected = extractStringField(json, "expected_output");

            if (desc == null || desc.isBlank()) return null;
            return new ChainStep(
                    stepNum > 0 ? stepNum : 1,
                    desc,
                    action != null ? action.trim().toLowerCase() : "shell",
                    expected != null ? expected : "");
        } catch (Exception e) {
            return null;
        }
    }

    private ChainResult singleStepFallback(String goal) {
        List<ChainStep> steps = List.of(new ChainStep(1, goal, "shell", "execute as single step"));
        return new ChainResult(UUID.randomUUID(), goal, steps, 1, null);
    }

    // ── JSON extraction utilities (no external dependencies) ───

    private String extractResponseField(String json) {
        String text = extractStringField(json, "response");
        if (text != null && !text.isBlank()) return text;

        int msgIdx = json.indexOf("\"message\":");
        if (msgIdx >= 0) {
            int contentIdx = json.indexOf("\"content\":", msgIdx);
            if (contentIdx >= 0) {
                text = extractStringAt(json, contentIdx + "\"content\":".length());
                if (text != null && !text.isBlank()) return text;
            }
        }
        return json; // fallback: return raw
    }

    private String extractStringField(String json, String fieldName) {
        String search = "\"" + fieldName + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) {
            // Try unquoted value
            search = "\"" + fieldName + "\":";
            start = json.indexOf(search);
            if (start < 0) return null;
            start += search.length();
            // Skip whitespace
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
            if (start < json.length() && json.charAt(start) == '"') {
                return extractStringAt(json, start + 1);
            }
            return null;
        }
        return extractStringAt(json, start + search.length());
    }

    private String extractStringAt(String json, int pos) {
        StringBuilder val = new StringBuilder();
        for (int i = pos; i < json.length(); i++) {
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

    private double extractDoubleField(String json, String fieldName) {
        String search = "\"" + fieldName + "\":";
        int start = json.indexOf(search);
        if (start < 0) return 0;
        start += search.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        StringBuilder num = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == '-' || c == 'e' || c == 'E') num.append(c);
            else break;
        }
        try { return Double.parseDouble(num.toString()); }
        catch (NumberFormatException e) { return 0; }
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

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }

    // ── Statistics ─────────────────────────────────────────────

    public int chainsCreated() { return chainsCreated; }
    public int chainsCompleted() { return chainsCompleted; }
    public int totalSteps() { return totalSteps; }
    public int stepsExecuted() { return stepsExecuted; }
    public double chainCompletionRate() { return chainsCreated == 0 ? 0 : (double) chainsCompleted / chainsCreated; }

    // ── Data types ─────────────────────────────────────────────

    /**
     * A single step in a prompt chain.
     */
    public static class ChainStep {
        public final int index;
        public final String description;
        public final String action;
        public final String expectedOutput;
        public String resultText;
        public boolean success;

        public ChainStep(int index, String description, String action, String expectedOutput) {
            this.index = index;
            this.description = description;
            this.action = action;
            this.expectedOutput = expectedOutput;
            this.resultText = null;
            this.success = false;
        }

        public ChainStep withResult(String text, boolean ok) {
            ChainStep copy = new ChainStep(index, description, action, expectedOutput);
            copy.resultText = text;
            copy.success = ok;
            return copy;
        }

        @Override
        public String toString() {
            return "Step " + index + ": " + description + " [" + action + "]"
                    + (resultText != null ? " → " + (success ? "✓" : "✗") : " ⏳");
        }
    }

    /**
     * Result of decomposing a complex goal into a chain.
     */
    public static class ChainResult {
        public final UUID id;
        public final String complexGoal;
        public final List<ChainStep> steps;
        public final int totalSteps;
        public final String context;
        public String synthesizedResult;

        public ChainResult(UUID id, String complexGoal, List<ChainStep> steps,
                           int totalSteps, String context) {
            this.id = id;
            this.complexGoal = complexGoal;
            this.steps = new ArrayList<>(steps);
            this.totalSteps = totalSteps;
            this.context = context;
            this.synthesizedResult = null;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Chain[");
            sb.append(totalSteps).append(" steps]: ").append(truncate(complexGoal, 80));
            for (ChainStep s : steps) {
                sb.append("\n  ").append(s);
            }
            return sb.toString();
        }
    }
}
