package de.metis.modules.planner;

import de.metis.kernel.action.ActionResult;
import de.metis.kernel.goal.Goal;
import de.metis.kernel.memory.Experience;
import de.metis.kernel.meta.MetaCognition;
import de.metis.kernel.planner.Planner;
import de.metis.kernel.workspace.ContentItem;
import de.metis.kernel.world.Belief;
import de.metis.kernel.world.WorldModel;

import de.metis.modules.evolution.ModelRegistry;
import de.metis.kernel.safety.OutputValidator;

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
 * {@link de.metis.kernel.planner.EvolvableModule}). The prompt template,
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

    // ── Model fallback chain (1.3) ───────────────────────────
    private final List<String> fallbackModels = new ArrayList<>();
    private final Map<String, Integer> modelFallbackCounts = new LinkedHashMap<>();
    private static final boolean ENABLE_FALLBACK_CHAIN = true;

    // ── Statistics ─────────────────────────────────────────────
    private int llmCalls = 0;
    private int llmFailures = 0;
    private int fallbackUses = 0;
    private int modelFallbackUses = 0;  // count of model-level fallbacks within Tier 1
    private Instant lastLlmCall;

    // ── Latency & Token Tracking (Phase 2.5.2) ───────────────
    private long totalLatencyMs = 0;
    private long lastCallLatencyMs = 0;
    private long totalPromptTokens = 0;
    private long totalResponseTokens = 0;
    private long lastPromptTokens = 0;
    private long lastResponseTokens = 0;

    // ── Planning metrics (Huyen Kap. 6) ──────────────────────
    private int totalPlansGenerated = 0;
    private int validPlanCount = 0;
    private int invalidPlanCount = 0;
    private int emptyPlanCount = 0;  // planner returned nothing
    private final Map<String, Integer> actionUsageCount = new ConcurrentHashMap<>();
    private final Map<String, Integer> actionErrorCount = new ConcurrentHashMap<>();

    // ── Output validation (Huyen Kap. 10) ────────────────────
    private final OutputValidator outputValidator = new OutputValidator();

    // ── ReAct state ──────────────────────────────────────────
    private String lastThought = null;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Create with defaults: miniedi Ollama, default model.
     * Use {@link #OllamaPlanner(String, ModelRegistry, Duration)} for auto-selection.
     */
    public OllamaPlanner() {
        this("http://192.168.22.204:11434/api/generate",
             "nemotron-cascade-2:30b",
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
        // Default fallback chain: mistral-small3.1 → nemotron → qwen3.6
        this.fallbackModels.addAll(List.of(
            "mistral-small3.1:24b",
            "nemotron:latest",
            "qwen3.6:latest"
        ));
    }

    /**
     * Create with ModelRegistry for automatic model selection.
     * The registry is queried each planning call (cached internally by registry).
     */
    public OllamaPlanner(String ollamaUrl, ModelRegistry registry, Duration timeout) {
        this.ollamaUrl = ollamaUrl;
        this.modelProvider = registry::planningModel;
        this.timeout = timeout;
        // Default fallback chain: mistral-small3.1 → nemotron → qwen3.6
        this.fallbackModels.addAll(List.of(
            "mistral-small3.1:24b",
            "nemotron:latest",
            "qwen3.6:latest"
        ));
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

    /**
     * Configure custom model fallback chain (1.3).
     * When the primary model fails, each fallback is tried in order.
     * @param models ordered list of model names to try as fallbacks
     */
    public OllamaPlanner withFallbackModels(List<String> models) {
        this.fallbackModels.clear();
        if (models != null) this.fallbackModels.addAll(models);
        return this;
    }

    /**
     * Add a single fallback model to the chain.
     */
    public OllamaPlanner addFallbackModel(String model) {
        if (model != null && !model.isBlank()) this.fallbackModels.add(model);
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

        totalPlansGenerated++;

        // ── Tier 1: LLM reasoning with Evaluator-Optimizer loop ──
        EvaluatedPlan bestPlan = planViaOllamaWithOptimizer(goal, recentHistory, broadcast, meta);

        if (bestPlan != null && bestPlan.action != null && !bestPlan.action.isBlank()
                && availableActions.contains(bestPlan.action)) {
            LOG.fine(() -> "Ollama planned (optimized): " + bestPlan.action
                    + " confidence=" + String.format("%.2f", bestPlan.confidence)
                    + " iterations=" + bestPlan.iterations
                    + " for goal: " + goal.description());
            lastPlanConfidence = bestPlan.confidence;
            lastThought = bestPlan.thought;
            validPlanCount++;
            actionUsageCount.merge(bestPlan.action, 1, Integer::sum);
            return List.of(bestPlan.action);
        }

        // Fallback to simple plan if optimizer produced nothing
        String action = planViaOllama(goal, recentHistory, broadcast, meta);

        if (action != null && !action.isBlank() && availableActions.contains(action)) {
            validPlanCount++;
            actionUsageCount.merge(action, 1, Integer::sum);
            // Self-reflect: let the model critique its own decision
            String reflection = selfReflect(goal, action);
            if (reflection != null) {
                LOG.fine(() -> "Self-reflection: " + reflection);
                storeReflection(goal, action, reflection);
            }
            LOG.fine(() -> "Ollama planned: " + action + " for goal: " + goal.description());
            return List.of(action);
        }

        // ── Tier 2: Learned mapping ────────────────────────────
        fallbackUses++;
        String learned = planViaLearnedMapping(goal, broadcast);
        if (learned != null) {
            validPlanCount++;
            actionUsageCount.merge(learned, 1, Integer::sum);
            LOG.fine(() -> "Learned fallback: " + learned + " for goal: " + goal.description());
            return List.of(learned);
        }

        // ── Tier 3: Keyword heuristic ──────────────────────────
        String keyword = planViaKeywords(goal, broadcast);
        if (keyword != null) {
            validPlanCount++;
            actionUsageCount.merge(keyword, 1, Integer::sum);
            LOG.fine(() -> "Keyword fallback: " + keyword + " for goal: " + goal.description());
            return List.of(keyword);
        }

        // No plan found
        emptyPlanCount++;
        return Collections.emptyList();
    }

    /**
     * Tier 1: Call Ollama with structured context and parse JSON response.
     * <p>
     * <b>Model Fallback Chain (1.3):</b> If the primary model fails,
     * automatically retry with each fallback model (nemotron → qwen3.6 → mistral-small3.1).
     * Only if ALL models fail does the call return null, falling through to
     * Tier 2 (learned) and Tier 3 (keyword).
     */
    private String planViaOllama(Goal goal, List<Experience> recentHistory,
                                  List<ContentItem> broadcast, MetaCognition meta) {
        llmCalls++;
        lastLlmCall = Instant.now();

        String prompt = buildPlanningPrompt(goal, recentHistory, broadcast, meta);

        // --- Try primary model first ---
        String primaryModel = resolveModel();
        String result = callOllamaModel(primaryModel, prompt);
        if (result != null) return result;

        // --- Model Fallback Chain (1.3) ---
        if (ENABLE_FALLBACK_CHAIN && !fallbackModels.isEmpty()) {
            List<String> modelsToTry = new ArrayList<>();
            // Add all fallback models that differ from the primary
            for (String fb : fallbackModels) {
                if (!fb.equals(primaryModel)) {
                    modelsToTry.add(fb);
                }
            }
            // Also add qwen3.6:latest as ultimate LLM fallback if not already in chain
            if (!modelsToTry.contains("qwen3.6:latest")
                    && !"qwen3.6:latest".equals(primaryModel)) {
                modelsToTry.add("qwen3.6:latest");
            }

            for (String fallbackModel : modelsToTry) {
                LOG.info(() -> "Model fallback: trying " + fallbackModel
                        + " (primary " + primaryModel + " failed)");
                modelFallbackUses++;
                modelFallbackCounts.merge(fallbackModel, 1, Integer::sum);
                String fallbackResult = callOllamaModel(fallbackModel, prompt);
                if (fallbackResult != null) {
                    LOG.info(() -> "Model fallback SUCCESS: " + fallbackModel
                            + " returned action=" + fallbackResult);
                    return fallbackResult;
                }
                LOG.warning("Model fallback " + fallbackModel + " also failed");
            }

            LOG.warning("All models in fallback chain exhausted");
        }

        // All LLM attempts failed
        llmFailures++;
        return null;
    }

    /**
     * Call a specific Ollama model with the planning prompt.
     * Returns the parsed action name or null on failure.
     */
    private String callOllamaModel(String modelName, String prompt) {
        long startMs = System.currentTimeMillis();
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
                        "num_predict": 256,
                        "num_ctx": 4096
                      },
                      "keep_alive": "10m"
                    }
                    """, modelName, escapeJson(prompt));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            // ── Track latency + tokens (Phase 2.5.2) ──────────────
            lastCallLatencyMs = System.currentTimeMillis() - startMs;
            totalLatencyMs += lastCallLatencyMs;
            trackTokens(response.body());

            if (response.statusCode() != 200) {
                LOG.warning("Ollama model " + modelName + " returned " + response.statusCode());
                return null;
            }

            String responseText = extractResponseField(response.body());
            if (responseText == null || responseText.isBlank()) {
                LOG.warning("Ollama model " + modelName + " response had no text field");
                return null;
            }

            // Parse JSON from response
            ParsedPlan plan = parsePlanResponse(responseText);
            if (plan != null && plan.action != null && !plan.action.isBlank()) {
                // Phase 6: Output validation (Huyen Kap. 10 — Guardrails)
                OutputValidator.ValidationResult validation = outputValidator
                        .validatePlannerOutput(plan.action, plan.thought,
                                plan.confidence, responseText);
                if (!validation.valid()) {
                    LOG.warning("Planner output blocked by validator (" + modelName
                            + "): " + validation.reason());
                    outputValidator.recordValidation(false);
                    // Don't return invalid — let fallback chain continue
                } else {
                    outputValidator.recordValidation(true);
                    LOG.fine(() -> "LLM plan (" + modelName + "): action=" + plan.action
                            + " confidence=" + String.format("%.2f", plan.confidence)
                            + " reason=" + plan.reasoning);
                    return plan.action.trim().toLowerCase();
                }
            }

            // If format:json returned valid JSON but parsePlanResponse didn't find action,
            // try parsing the raw response as the JSON object directly
            ParsedPlan rawPlan = parsePlanResponse(response.body());
            if (rawPlan != null && rawPlan.action != null && !rawPlan.action.isBlank()) {
                OutputValidator.ValidationResult validation = outputValidator
                        .validatePlannerOutput(rawPlan.action, rawPlan.thought,
                                rawPlan.confidence, response.body());
                if (!validation.valid()) {
                    LOG.warning("Planner output (raw) blocked by validator (" + modelName
                            + "): " + validation.reason());
                    outputValidator.recordValidation(false);
                } else {
                    outputValidator.recordValidation(true);
                    LOG.fine(() -> "LLM plan (" + modelName + " raw): action=" + rawPlan.action);
                    return rawPlan.action.trim().toLowerCase();
                }
            }

            LOG.warning("Failed to parse action from " + modelName + ": "
                    + responseText.substring(0, Math.min(200, responseText.length())));
            return null;

        } catch (Exception e) {
            LOG.warning("Ollama model " + modelName + " call failed: " + e.getMessage());
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

        // ── System role + Chain-of-Thought ──
        sb.append("You are Metis, an autonomous agent that selects the single best action per goal.\n");
        sb.append("Think step by step before answering:\n");
        sb.append("  (1) ANALYZE: What does the goal really need? What type of work?\n");
        sb.append("  (2) MATCH: Which action category fits best?\n");
        sb.append("  (3) CHECK: Did this action fail recently for similar goals? IMPORTANT: 0 uses ≠ 0% success. An action with 0 execution count is UNTESTED (potentially great!), not failed. Only avoid actions with low success rate AND actual execution history.\n");
        sb.append("  (4) DECIDE: Pick the action with highest expected success. Be decisive — ONE action.\n\n");

        sb.append("RULES:\n");
        sb.append("- EXPLORATION goals (category='exploration'): ALWAYS pick untested actions (0 uses) over shell/http. Exploration is for discovering new capabilities, not repeating old ones.\n");
        sb.append("- Prefer actions with proven success rates over ones with proven failures\n");
        sb.append("- UNTESTED actions (0 execution count) are OPPORTUNITIES, not risks. Treat them as high-value exploration targets.\n");
        sb.append("- Shell is the LAST RESORT fallback — only when NO specialized action fits (filesystem, memory, self-analysis, etc. are ALL more specialized than shell)\n");

        // ── Action catalog ──
        sb.append("ACTION CATALOG:\n");
        sb.append("- shell: run Linux commands (system checks, process info, general exploration)\n");
        sb.append("- http: make HTTP requests (health checks, API calls, endpoint testing)\n");
        sb.append("- webscrape: extract human-readable text from web pages\n");
        sb.append("- filesystem-list: list directory contents, discover file structure\n");
        sb.append("- filesystem-read: read complete file contents by path\n");
        sb.append("- api-explore: discover and probe HTTP endpoints on a target host\n");
        sb.append("- linux-explore-system: deep system probe (processes, memory, disk, network)\n");
        sb.append("- memory-query: search the agent's own long-term knowledge base\n");
        sb.append("- self-analyze: inspect agent's own performance metrics and state\n");
        sb.append("- javasandbox: execute safe, sandboxed Java code experiments\n");
        sb.append("- prompt-chain: decompose complex multi-step goals into sequential sub-goals, execute each with context from previous results, and synthesize final answer (Pattern: Decompose→Execute→Aggregate)\n\n");

        // ── Rich Few-Shot examples with thought (ReAct format) ──
        sb.append("FEW-SHOT EXAMPLES:\n");
        sb.append("Goal: Check system status → {\"thought\":\"System health requires shell commands like uptime or free\",\"action\":\"shell\",\"reasoning\":\"shell commands for system health check\",\"confidence\":0.90}\n");
        sb.append("Goal: Verify if webserver is reachable → {\"thought\":\"HTTP health check is the direct way to test reachability\",\"action\":\"http\",\"reasoning\":\"HTTP GET to health endpoint\",\"confidence\":0.95}\n");
        sb.append("Goal: Extract article text from a URL → {\"thought\":\"Webscrape extracts readable text, better than raw HTTP\",\"action\":\"webscrape\",\"reasoning\":\"webscrape extracts readable text from HTML\",\"confidence\":0.90}\n");
        sb.append("Goal: List all files in /etc directory → {\"thought\":\"Directory listing is a filesystem operation\",\"action\":\"filesystem-list\",\"reasoning\":\"directory listing via filesystem action\",\"confidence\":0.95}\n");
        sb.append("Goal: Read the contents of config.json → {\"thought\":\"Reading a specific file by path needs filesystem-read\",\"action\":\"filesystem-read\",\"reasoning\":\"read specific file by path\",\"confidence\":0.95}\n");
        sb.append("Goal: Discover available REST endpoints → {\"thought\":\"API exploration probes endpoints systematically, better than single HTTP call\",\"action\":\"api-explore\",\"reasoning\":\"probe HTTP endpoints systematically\",\"confidence\":0.85}\n");
        sb.append("Goal: Get detailed system resource overview → {\"thought\":\"Deep system probe covers multiple resource dimensions\",\"action\":\"linux-explore-system\",\"reasoning\":\"deep system probe for resources\",\"confidence\":0.85}\n");
        sb.append("Goal: What do I know about network configuration? → {\"thought\":\"This is a knowledge retrieval task, not an active probe\",\"action\":\"memory-query\",\"reasoning\":\"search agent's long-term knowledge base\",\"confidence\":0.80}\n");
        sb.append("Goal: How well am I performing lately? → {\"thought\":\"Self-analysis inspects the agent's own metrics, not external systems\",\"action\":\"self-analyze\",\"reasoning\":\"self-analysis of performance metrics\",\"confidence\":0.85}\n");
        sb.append("Goal: Run a Java math experiment safely → {\"thought\":\"Safe code execution needs sandbox, not raw shell\",\"action\":\"javasandbox\",\"reasoning\":\"sandboxed Java execution for safe code\",\"confidence\":0.90}\n");
        sb.append("Goal: Research a topic and create a structured report → {\"thought\":\"Complex multi-step task needs decomposition — web research, extract, structure, save\",\"action\":\"prompt-chain\",\"reasoning\":\"multi-step research task best handled by prompt chaining\",\"confidence\":0.85}\n");
        sb.append("Goal: Investigate system security and generate audit report → {\"thought\":\"System audit requires multiple steps — probe, analyze, aggregate, report\",\"action\":\"prompt-chain\",\"reasoning\":\"systematic audit via chained sub-goals\",\"confidence\":0.85}\n\n");

        // ── Goal context ──
        sb.append("CURRENT GOAL:\n");
        sb.append("Description: ").append(goal.description()).append("\n");
        sb.append("Category: ").append(goal.category()).append("\n");
        sb.append("Priority: ").append(goal.priority()).append("/100\n");
        sb.append("Expected reward: ").append(String.format("%.2f", goal.expectedReward())).append("\n\n");

        // ── Available actions ──
        sb.append("AVAILABLE ACTIONS: ").append(String.join(", ", availableActions)).append("\n\n");

        // ── Workspace attention (broadcast) ──
        if (!broadcast.isEmpty()) {
            sb.append("ATTENTION FOCUS (what the agent is currently attending to):\n");
            for (ContentItem item : broadcast) {
                sb.append("- [").append(item.source()).append("] ")
                        .append(item.summary()).append("\n");
            }
            sb.append("\n");
        }

        // ── World model beliefs ──
        if (worldModel != null) {
            List<Belief> relevant = worldModel.query(goal.description(), 5);
            if (!relevant.isEmpty()) {
                sb.append("WORLD KNOWLEDGE (beliefs relevant to this goal):\n");
                for (Belief b : relevant) {
                    sb.append("- ").append(b.statement())
                            .append(" (confidence: ").append(String.format("%.2f", b.confidence())).append(")\n");
                }
                sb.append("\n");
            }
        }

        // ── Recent experiences ──
        if (!recentHistory.isEmpty()) {
            sb.append("RECENT EXPERIENCES (last 5, pay attention to failures!):\n");
            List<Experience> recent = recentHistory.size() > 5
                    ? recentHistory.subList(recentHistory.size() - 5, recentHistory.size())
                    : recentHistory;
            for (Experience exp : recent) {
                sb.append("- ").append(exp.actionName())
                        .append(exp.success() ? " ✓" : " ✗ FAILED")
                        .append(" | goal: ").append(exp.goalDescription())
                        .append(" | error: ").append(String.format("%.2f", exp.predictionError())).append("\n");
            }
            sb.append("\n");
        }

        // ── Meta-cognitive state ──
        sb.append("AGENT INTERNAL STATE:\n");
        sb.append("Confidence: ").append(String.format("%.2f", meta.confidence())).append("\n");
        sb.append("Surprised: ").append(meta.isSurprised()).append("\n");
        sb.append("Rolling error: ").append(String.format("%.2f", meta.rollingError())).append("\n");
        sb.append("Observations: ").append(meta.observationCount()).append("\n\n");

        // ── Learned success rates ──
        sb.append("LEARNED ACTION SUCCESS RATES (0 uses = untested opportunity, not a risk):\n");
        if (planningAttempts.isEmpty()) {
            sb.append("(no learned data yet — use examples above as guide)\n");
        } else {
            for (var entry : planningAttempts.entrySet()) {
                String key = entry.getKey();
                int att = entry.getValue();
                int succ = planningSuccess.getOrDefault(key, 0);
                double rate = att > 0 ? (double) succ / att : 0.0;
                if (att >= MIN_ATTEMPTS) {
                    sb.append("- ").append(key)
                            .append(": ").append(String.format("%.0f%%", rate * 100))
                            .append(" (").append(succ).append("/").append(att).append(")");
                    // Bugfix: Only flag as AVOID if action has actual failures (rate==0 AND attempts>0)
                    // A 0-count action is untested, not failed — listed separately below
                    if (rate == 0.0 && att > 0) sb.append(" ⚠️ AVOID (proven failure)");
                    sb.append("\n");
                }
            }

            // Show untested actions as OPPORTUNITIES
            List<String> untested = new ArrayList<>();
            for (String actionName : availableActions) {
                boolean hasHistory = false;
                for (String key : planningAttempts.keySet()) {
                    if (key.endsWith(":" + actionName) && planningAttempts.get(key) > 0) {
                        hasHistory = true;
                        break;
                    }
                }
                if (!hasHistory) untested.add(actionName);
            }
            if (!untested.isEmpty()) {
                sb.append("UNTESTED ACTIONS (0 uses — excellent exploration targets!): ");
                sb.append(String.join(", ", untested)).append("\n");
            }
        }
        sb.append("\n");

        // ── Final instruction ──
        sb.append("DECISION: Based on the above analysis, which SINGLE action should execute NOW?\n");
        sb.append("Respond with ONLY this JSON (no markdown, no extra text):\n");
        sb.append("{\"thought\":\"<your step-by-step reasoning>\",\"action\":\"<name>\",\"reasoning\":\"<one sentence why>\",\"confidence\":<0.0-1.0>}");

        return sb.toString();
    }

    /** Current plan confidence (set by evaluator-optimizer). */
    private double lastPlanConfidence = 0.5;
    /** Stored self-reflections for learning. */
    private final List<String> recentReflections = new ArrayList<>();
    private static final int MAX_REFLECTIONS = 20;

    /**
     * Evaluator-Optimizer loop (Huyen K.10): Plan → Evaluate → Improve.
     * Runs up to 2 iterations, keeping the highest-confidence plan.
     */
    private EvaluatedPlan planViaOllamaWithOptimizer(Goal goal, List<Experience> recentHistory,
                                                      List<ContentItem> broadcast, MetaCognition meta) {
        EvaluatedPlan best = null;

        for (int iteration = 0; iteration < 2; iteration++) {
            String action;
            double confidence = 0.5;

            if (iteration == 0) {
                // First pass: standard planning prompt
                action = planViaOllama(goal, recentHistory, broadcast, meta);
                if (action == null) continue;

                // Extract confidence from parsed plan (via last response)
                confidence = lastPlanConfidence;
            } else {
                // Second pass: evaluate previous plan and improve
                if (best == null) break;

                String evalPrompt = buildEvaluatorPrompt(goal, best.action, best.confidence,
                        recentHistory, meta);
                String improvedAction = callOllamaModel(resolveModel(), evalPrompt);
                if (improvedAction == null) break;
                action = improvedAction;
                confidence = lastPlanConfidence;
            }

            if (action != null && availableActions.contains(action)) {
                if (best == null || confidence > best.confidence) {
                    best = new EvaluatedPlan(action, lastThought, confidence, iteration + 1);
                }
                // If confidence is already high, stop optimizing
                if (confidence >= 0.85) break;
            }
        }

        return best;
    }

    /**
     * Self-Reflection (Prompting K.3): Ask the model to critique its own planning decision.
     * Returns a brief assessment or null if reflection fails.
     */
    private String selfReflect(Goal goal, String chosenAction) {
        String prompt = "You previously chose action '" + chosenAction
                + "' for goal: '" + goal.description() + "'.\n"
                + "Critique your decision. Was it the best choice? What alternatives exist?\n"
                + "Respond with ONE sentence. Start with 'CORRECT:' if right, 'ALTERNATIVE:' if another action would be better.\n"
                + "Example: CORRECT: shell is the best choice for system status checks.\n"
                + "Your critique:";

        try {
            String jsonBody = String.format("""
                    {
                      "model": "%s",
                      "prompt": %s,
                      "stream": false,
                      "options": {
                        "temperature": 0.3,
                        "num_predict": 100
                      }
                    }
                    """, resolveModel(), escapeJson(prompt));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;

            String text = extractResponseField(response.body());
            return text != null ? text.strip() : null;
        } catch (Exception e) {
            LOG.fine("Self-reflection failed: " + e.getMessage());
            return null;
        }
    }

    private void storeReflection(Goal goal, String action, String reflection) {
        recentReflections.add(goal.description() + " → " + action + ": " + reflection);
        while (recentReflections.size() > MAX_REFLECTIONS) {
            recentReflections.removeFirst();
        }
    }

    /** Build evaluator prompt for second optimization iteration. */
    private String buildEvaluatorPrompt(Goal goal, String previousAction, double previousConfidence,
                                         List<Experience> recentHistory, MetaCognition meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are evaluating a previous planning decision to find a better action.\n");
        sb.append("Respond with ONLY: {\"action\":\"<name>\",\"reasoning\":\"<why>\",\"confidence\":<0.0-1.0>}\n\n");
        sb.append("GOAL: ").append(goal.description()).append("\n");
        sb.append("Previous choice: ").append(previousAction)
                .append(" (confidence: ").append(String.format("%.2f", previousConfidence)).append(")\n");
        sb.append("AVAILABLE ACTIONS: ").append(String.join(", ", availableActions)).append("\n");
        sb.append("Can you find a BETTER action? Consider alternatives. If the previous choice was best, repeat it.\n\n");
        sb.append("Respond with ONLY:\n{\"action\":\"");
        return sb.toString();
    }

    private record EvaluatedPlan(String action, String thought, double confidence, int iterations) {}
    // … (planViaOllamaWithOptimizer method follows)

    /**
     * Extract the response text from Ollama's JSON wrapper.
     * Handles /api/generate ("response" field), thinking models ("thinking" field),
     * and /api/chat format ("message"."content" field).
     */
    private String extractResponseField(String json) {
        // Priority 1: /api/generate "response" field
        String text = extractJsonStringValue(json, "response");
        if (text != null && !text.isBlank()) return text;

        // Priority 2: /api/chat "message"."content" field
        int msgIdx = json.indexOf("\"message\":");
        if (msgIdx >= 0) {
            int contentIdx = json.indexOf("\"content\":", msgIdx);
            if (contentIdx >= 0) {
                text = extractJsonStringAt(json, contentIdx + "\"content\":".length());
                if (text != null && !text.isBlank()) return text;
            }
        }

        // Priority 3: "thinking" field (thinking models in /api/generate)
        text = extractJsonStringValue(json, "thinking");
        if (text != null && !text.isBlank()) return text;

        return null;
    }

    /** Extract a top-level JSON string value by key. */
    private String extractJsonStringValue(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int start = json.indexOf(searchKey);
        if (start < 0) return null;
        return extractJsonStringAt(json, start + searchKey.length());
    }

    /** Extract a JSON string value starting at a position after the opening quote. */
    private String extractJsonStringAt(String json, int pos) {
        StringBuilder val = new StringBuilder();
        for (int i = pos; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n' -> { val.append('\n'); i++; }
                    case 't' -> { val.append('\t'); i++; }
                    case 'r' -> { val.append('\r'); i++; }
                    case '\"' -> { val.append('"'); i++; }
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
            String thought = extractJsonStringField(json, "thought");
            String reasoning = extractJsonStringField(json, "reasoning");
            double confidence = extractJsonDoubleField(json, "confidence");

            if (action != null) {
                return new ParsedPlan(action,
                        thought != null ? thought : "",
                        reasoning != null ? reasoning : "", confidence);
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
        if (result.success()) {
            planningSuccess.merge(key, 1, Integer::sum);
        } else {
            actionErrorCount.merge(action, 1, Integer::sum);
        }
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
        return modelProvider != null ? modelProvider.get() : "nemotron-cascade-2:30b";
    }

    /**
     * Parse token counts from Ollama response (Phase 2.5.2).
     * Extracts prompt_eval_count and eval_count from the JSON body.
     */
    private void trackTokens(String responseBody) {
        try {
            int peIdx = responseBody.indexOf("\"prompt_eval_count\":");
            if (peIdx >= 0) {
                String num = extractNumberAt(responseBody, peIdx + "\"prompt_eval_count\":".length());
                if (num != null) {
                    lastPromptTokens = Long.parseLong(num);
                    totalPromptTokens += lastPromptTokens;
                }
            }
            int eIdx = responseBody.indexOf("\"eval_count\":");
            if (eIdx >= 0) {
                String num = extractNumberAt(responseBody, eIdx + "\"eval_count\":".length());
                if (num != null) {
                    lastResponseTokens = Long.parseLong(num);
                    totalResponseTokens += lastResponseTokens;
                }
            }
        } catch (Exception e) {
            // Non-critical — token tracking is best-effort
        }
    }

    private String extractNumberAt(String json, int pos) {
        StringBuilder num = new StringBuilder();
        for (int i = pos; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c)) num.append(c);
            else if (num.length() > 0) break;
        }
        return num.length() > 0 ? num.toString() : null;
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

    // ── Accessors ───────────────────────────────────────────────

    /** Expose raw planning attempts for persistence. */
    public Map<String, Integer> rawPlanningAttempts() {
        return Map.copyOf(planningAttempts);
    }
    /** Expose raw planning successes for persistence. */
    public Map<String, Integer> rawPlanningSuccesses() {
        return Map.copyOf(planningSuccess);
    }

    public int llmCalls() { return llmCalls; }
    public int llmFailures() { return llmFailures; }
    public int fallbackUses() { return fallbackUses; }
    public int modelFallbackUses() { return modelFallbackUses; }
    public long totalLatencyMs() { return totalLatencyMs; }
    public long lastCallLatencyMs() { return lastCallLatencyMs; }
    public long avgLatencyMs() { return llmCalls == 0 ? 0 : totalLatencyMs / llmCalls; }
    public long totalPromptTokens() { return totalPromptTokens; }
    public long totalResponseTokens() { return totalResponseTokens; }
    public long lastPromptTokens() { return lastPromptTokens; }
    public long lastResponseTokens() { return lastResponseTokens; }
    public double tokensPerCall() { return llmCalls == 0 ? 0 : (double) (totalPromptTokens + totalResponseTokens) / llmCalls; }
    public Map<String, Integer> modelFallbackCounts() { return Map.copyOf(modelFallbackCounts); }
    public List<String> fallbackModelChain() { return List.copyOf(fallbackModels); }
    public int totalPlansGenerated() { return totalPlansGenerated; }
    public int validPlanCount() { return validPlanCount; }
    public int emptyPlanCount() { return emptyPlanCount; }
    public Map<String, Integer> actionUsageCount() { return Map.copyOf(actionUsageCount); }
    public Map<String, Integer> actionErrorCount() { return Map.copyOf(actionErrorCount); }
    public OutputValidator outputValidator() { return outputValidator; }
    public String lastThought() { return lastThought; }
    public Instant lastLlmCall() { return lastLlmCall; }

    /**
     * How often the LLM was successfully used (as fraction of attempts).
     */
    public double llmSuccessRate() {
        return llmCalls == 0 ? 0.0 : (double) (llmCalls - llmFailures) / llmCalls;
    }

    // ── Parsed plan record ──────────────────────────────────────

    private record ParsedPlan(String action, String thought, String reasoning, double confidence) {}
}
