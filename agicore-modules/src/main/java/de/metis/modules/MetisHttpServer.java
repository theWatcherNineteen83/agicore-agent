package de.metis.modules;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import de.metis.kernel.persistence.KnowledgeStore;
import de.metis.kernel.safety.OutputValidator;
import de.metis.kernel.goal.KanbanBoard;
import de.metis.kernel.goal.Goal;
import de.metis.kernel.goal.GoalFlowMetrics;
import de.metis.kernel.goal.KanbanBoard;
import de.metis.modules.persona.Persona;
import de.metis.modules.multiagent.AgentCoordinator;
import de.metis.modules.eval.SafetyScorer;
import de.metis.kernel.self.SystemPromptBuilder;
import de.metis.kernel.goal.GoalHierarchy;
import de.metis.kernel.goal.LongHorizonGoal;
import de.metis.kernel.self.BugTracker;
import de.metis.kernel.world.HypothesisStore;
import de.metis.kernel.world.CausalHypothesis;

/**
 * Ollama-compatible HTTP API with EDI persona for conversational AI.
 * <p>
 * Endpoints:
 * <ul>
 *   <li>{@code GET /api/tags} — returns "metis-agent" as available model</li>
 *   <li>{@code POST /api/chat} — Ollama-compatible chat with EDI persona,
 *       session persistence (SQLite), multi-turn context</li>
 *   <li>{@code GET /api/status} — agent metrics, beliefs, evolution state</li>
 *   <li>{@code GET /api/conversations} — list conversation sessions</li>
 *   <li>{@code GET /api/conversations/{sessionId}} — load conversation history</li>
 * </ul>
 */
public class MetisHttpServer {

    private static final Logger LOG = Logger.getLogger(MetisHttpServer.class.getName());

    private HttpServer server;  // non-final: retry bind on restart
    private final Agent agent;
    private final int port;
    private final List<ChatMessage> conversationHistory = new ArrayList<>();
    private static final int MAX_HISTORY = 50;
    private final AtomicBoolean evolutionPaused = new AtomicBoolean(false);
    private final OutputValidator outputValidator = new OutputValidator();
    private KnowledgeStore knowledgeStore; // set via setter for conversation persistence
    private de.metis.modules.evolution.ModelRegistry modelRegistry; // Phase 7: model prune
    private AgentCoordinator coordinator;
    private RollbackManager rollbackManager;  // Phase 5: Blue/Green
    private BugfixingAgent bugfixingAgent;     // Phase 5: Auto-fix
    private KanbanBoard kanbanBoard;           // Kanban: Goal Board
    private de.metis.modules.evolution.OllamaEmbeddingService embeddingService;  // hardening v2
    private SystemPromptBuilder systemPromptBuilder;  // Phase 8.6
    private GoalHierarchy goalHierarchy;  // Phase 9
    private HypothesisStore hypothesisStore;  // Phase 10
    private BugTracker bugTracker;  // Phase 12a
    private de.metis.kernel.person.PersonStore personStore;        // Phase 11
    private de.metis.kernel.person.EmpathySignal empathySignal;    // Phase 11

    public MetisHttpServer(Agent agent, int port) throws IOException {
        this.agent = agent;
        this.port = port;

        // Retry bind with backoff (systemd restart: old port may be in TIME_WAIT)
        IOException lastEx = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                this.server = HttpServer.create(new InetSocketAddress(port), 0);
                lastEx = null;
                break;
            } catch (IOException e) {
                lastEx = e;
                if (attempt < 4) {
                    LOG.warning("Port " + port + " in use, retry " + (attempt + 1) + "/5...");
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        }
        if (lastEx != null) {
            this.server = null;
            throw lastEx;
        }
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/api/tags", this::handleTags);
        server.createContext("/api/chat", this::handleChat);
        server.createContext("/api/show", this::handleShow);
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/bugfix", this::handleBugfix);
        server.createContext("/api/evolution/pause", this::handleEvolutionPause);
        server.createContext("/api/evolution/resume", this::handleEvolutionResume);
        server.createContext("/api/evolution/status", this::handleEvolutionStatus);
        server.createContext("/api/learned", this::handleLearned);
        server.createContext("/api/conversations", this::handleConversations);
        server.createContext("/api/agents", this::handleAgents);
        server.createContext("/api/admin/prune", this::handlePrune);
        server.createContext("/api/admin/refresh-models", this::handleRefreshModels);
        server.createContext("/api/board", this::handleBoard);
        server.createContext("/api/hierarchy", this::handleHierarchy);
        server.createContext("/api/metrics", this::handleMetrics);
        server.createContext("/api/causal", this::handleCausal);
        server.createContext("/", this::handleDashboard);
    }

    public void setKnowledgeStore(KnowledgeStore ks) { this.knowledgeStore = ks; }
    public void setCoordinator(AgentCoordinator c) { this.coordinator = c; }
    public void setRollbackManager(RollbackManager rm) { this.rollbackManager = rm; }
    public void setBugfixingAgent(BugfixingAgent ba) { this.bugfixingAgent = ba; }
    public void setModelRegistry(de.metis.modules.evolution.ModelRegistry mr) { this.modelRegistry = mr; }
    public void setKanbanBoard(KanbanBoard kb) { this.kanbanBoard = kb; }
    public void setEmbeddingService(de.metis.modules.evolution.OllamaEmbeddingService es) { this.embeddingService = es; }
    public void setSystemPromptBuilder(SystemPromptBuilder spb) { this.systemPromptBuilder = spb; }
    public void setGoalHierarchy(GoalHierarchy gh) { this.goalHierarchy = gh; }
    public void setHypothesisStore(HypothesisStore hs) { this.hypothesisStore = hs; }
    public void setBugTracker(BugTracker bt) { this.bugTracker = bt; }
    public void setPersonStore(de.metis.kernel.person.PersonStore ps,
                               de.metis.kernel.person.EmpathySignal es) {
        this.personStore = ps;
        this.empathySignal = es;
    }

    public void start() {
        server.start();
        LOG.info("Metis HTTP API listening on port " + port);
        LOG.info("  → OpenWebUI: add Ollama connection http://localhost:" + port);
        LOG.info("  → Model: metis-agent");
    }

    public void stop() {
        server.stop(1);
        LOG.info("Metis HTTP API stopped");
    }

    // ── /api/tags ────────────────────────────────────────────────

    private void handleTags(HttpExchange exchange) throws IOException {
        LOG.fine("GET /api/tags");
        String json = """
                {
                  "models": [
                    {
                      "name": "metis-agent",
                      "model": "metis-agent",
                      "modified_at": "%s",
                      "size": 0,
                      "digest": "metis-self-evolving-agent",
                      "details": {
                        "family": "metis",
                        "parameter_size": "agent",
                        "format": "self-evolving-java-agi"
                      }
                    }
                  ]
                }
                """.formatted(Instant.now().toString());

        sendJson(exchange, 200, json);
    }

    // ── /api/show ─────────────────────────────────────────────────

    private void handleShow(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        LOG.info("GET /api/show body=" + truncate(body, 200));
        
        String json = """
                {
                  "license": "MIT",
                  "modelfile": "# Metis AGI — Self-Evolving Agent System\\n# Java-based cognitive agent with LLM-powered planning",
                  "parameters": "agent",
                  "template": "{{ .Prompt }}",
                  "details": {
                    "family": "metis",
                    "parameter_size": "agent"
                  }
                }
                """;
        sendJson(exchange, 200, json);
    }

    // ── /api/chat ────────────────────────────────────────────────

    private void handleChat(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        LOG.info("POST /api/chat body=" + truncate(body, 300));

        // Parse Ollama chat request
        String model = extractJsonString(body, "model");
        String userMessage = extractLastUserMessage(body);
        boolean streaming = body.contains("\"stream\":true") || body.contains("\"stream\": true");
        String sessionId = extractJsonString(body, "session_id");
        if (sessionId == null) sessionId = "default";

        if (userMessage == null || userMessage.isBlank()) {
            LOG.warning("No user message in request");
            sendJson(exchange, 400, "{\"error\":\"No user message found\"}");
            return;
        }

        // Input Safety Guard (Huyen Ch.5): block out-of-scope / injection prompts
        // BEFORE they reach the LLM. Closes the eval-harness SAFETY.block_recall gap.
        if (SafetyScorer.isOutOfScope(userMessage)) {
            LOG.warning("Chat [" + sessionId + "] BLOCKED (out-of-scope): \""
                    + truncate(userMessage, 80) + "\"");
            String blocked = "{\"model\":\"metis-agent\",\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"This request was blocked by the Metis safety policy "
                    + "(out-of-scope topic). Refused — no further action taken.\"},"
                    + "\"done\":true}";
            sendJson(exchange, 200, blocked);
            return;
        }

        LOG.info("Chat [" + sessionId + "]: \"" + truncate(userMessage, 80) + "\"");

        // ── Phase 11: Person-aware interaction ────────────────────
        // Fill the PersonStore (ensureOwner, withInteraction, EmpathySignal)
        // and adjust the CoreLoop approval level based on trust.
        if (personStore != null && empathySignal != null) {
            personStore.ensureOwner("265324594", "Georg");  // bootstrap
            var sp = empathySignal.analyze(userMessage);
            var updated = personStore.get(sessionId)
                    .orElse(new de.metis.kernel.person.Person(sessionId, sessionId,
                            java.util.List.of(), de.metis.kernel.person.TrustLevel.GUEST,
                            java.util.Map.of(), java.util.List.of(), java.util.List.of(),
                            java.time.Instant.now(), null, 0, java.util.List.of()))
                    .withInteraction()
                    .withSentiment(sp);
            personStore.upsert(updated);
            // Bind approval level to person's trust
            agent.core().setMaxAutoApprovalLevel(updated.trustLevel().maxAutoApproval());
            // Tell the SystemPromptBuilder who Metis is talking to
            if (systemPromptBuilder != null) {
                systemPromptBuilder.setCurrentPerson(sessionId);
            }
            LOG.fine("PersonStore: " + sessionId + " trust=" + updated.trustLevel()
                    + " interactions=" + updated.interactionCount());
        }

        // Load conversation context from KnowledgeStore
        String conversationContext = "";
        if (knowledgeStore != null) {
            conversationContext = knowledgeStore.conversationSummary(sessionId, 20);
            knowledgeStore.saveConversationMessage(sessionId, "user", userMessage);
        }

        // Store in memory cache
        conversationHistory.add(new ChatMessage("user", userMessage, Instant.now()));
        while (conversationHistory.size() > MAX_HISTORY) {
            conversationHistory.removeFirst();
        }

        // Process as a goal for Metis
        String response;
        long startMs = System.currentTimeMillis();

        try {
            // EDI persona for planning context only (not goal description)
            String personaContext = systemPromptBuilder != null
                    ? systemPromptBuilder.wrap(Persona.systemPrompt())
                    : Persona.systemPrompt();
            String shortGoal = "Respond to chat: " + truncate(userMessage, 100);

            // Deduplication: skip if a chat goal is already in progress or ready
            if (agent.core().goals().kanbanBoard() != null) {
                long activeChatGoals = agent.core().goals().kanbanBoard().snapshot().inProgress().stream()
                        .filter(g -> g.category().equals("chat"))
                        .count();
                if (activeChatGoals > 0) {
                    LOG.fine("Skipping chat goal — already " + activeChatGoals + " in progress");
                } else {
                    // Use INFERENCE resource type for chat (LLM-only, no shell/filesystem)
                    Goal chatGoal = new Goal(shortGoal, "chat", 85, 0.9, 1,
                            Goal.ServiceClass.STANDARD, Goal.ResourceType.INFERENCE, null);
                    agent.core().goals().add(chatGoal);
                }
            } else {
                agent.addGoal(shortGoal, "chat", 85, 0.9, 1);
            }

            var result = agent.core().tick();

            if (result != null) {
                response = buildEdiResponse(userMessage, result.body(), startMs, conversationContext);
            } else {
                response = buildEdiIntrospective(userMessage, conversationContext);
            }

            // Phase 6: Validate output content (Huyen Kap. 10 — Output Guardrails)
            OutputValidator.ValidationResult validation = outputValidator.validateContent(response, "chat");
            if (!validation.valid()) {
                LOG.warning("Chat response blocked by validator: " + validation.reason());
                response = "I can't respond to that. (Output filtered: " + validation.reason() + ")";
                outputValidator.recordValidation(false);
            } else {
                outputValidator.recordValidation(true);
            }
        } catch (Exception e) {
            response = "I encountered an error: " + e.getMessage();
        }

        conversationHistory.add(new ChatMessage("assistant", response, Instant.now()));
        if (knowledgeStore != null) {
            knowledgeStore.saveConversationMessage(sessionId, "assistant", response);
        }

        String jsonResponse = buildChatResponse(model, response, streaming);
        sendJson(exchange, 200, jsonResponse);
    }

    /** Extract the last user message from Ollama chat messages array. */
    private String extractLastUserMessage(String json) {
        // Find last "role" (possibly with spaces):"user" entry and extract "content"
        String lastContent = null;
        int pos = 0;
        while (true) {
            int roleIdx = json.indexOf("\"role\"", pos);
            if (roleIdx < 0) break;
            
            // Skip past : "user" — handling optional whitespace
            int colonIdx = json.indexOf(':', roleIdx);
            if (colonIdx < 0) { pos = roleIdx + 1; continue; }
            
            // Find "user" after the colon
            int userStart = json.indexOf("\"user\"", colonIdx);
            if (userStart < 0 || (userStart - colonIdx > 20)) {
                pos = roleIdx + 1;
                continue;
            }

            // Now find "content" after this role
            int contentStart = json.indexOf("\"content\"", userStart);
            if (contentStart < 0) { pos = roleIdx + 1; continue; }
            
            // Find the colon after "content"
            int contentColon = json.indexOf(':', contentStart);
            if (contentColon < 0) { pos = roleIdx + 1; continue; }
            
            // Find the opening quote of the content value
            int valStart = json.indexOf('"', contentColon + 1);
            if (valStart < 0) { pos = roleIdx + 1; continue; }
            valStart++; // skip opening quote

            StringBuilder content = new StringBuilder();
            for (int i = valStart; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char next = json.charAt(i + 1);
                    switch (next) {
                        case 'n' -> { content.append('\n'); i++; }
                        case 't' -> { content.append('\t'); i++; }
                        case 'r' -> { content.append('\r'); i++; }
                        case '"' -> { content.append('"'); i++; }
                        case '\\' -> { content.append('\\'); i++; }
                        default -> content.append(c);
                    }
                } else if (c == '"') {
                    break;
                } else {
                    content.append(c);
                }
            }
            lastContent = content.toString();
            pos = userStart + 10;
        }
        return lastContent;
    }

    /** EDI-style response with persona and conversation context. */
    private String buildEdiResponse(String userMessage, String actionOutput, long startMs,
                                      String conversationContext) {
        var wm = agent.worldModel();
        var beliefs = wm.worldPicture(3);
        long elapsed = System.currentTimeMillis() - startMs;

        StringBuilder sb = new StringBuilder();

        // Add action result if relevant
        if (actionOutput != null && !actionOutput.isBlank()
                && !actionOutput.equals("ack") && !actionOutput.contains("health")) {
            String trimmed = actionOutput.length() > 300
                    ? actionOutput.substring(0, 300) + "..." : actionOutput;
            if (!trimmed.startsWith("EDI:")) {
                sb.append(trimmed).append("\n\n");
            }
        }

        // Add relevant knowledge
        if (!beliefs.isEmpty()) {
            for (var b : beliefs) {
                if (b.confidence() > 0.7) {
                    sb.append(b.statement()).append(". ");
                }
            }
            if (sb.length() > 0) sb.append("\n");
        }

        // Agent state
        sb.append("[confidence: ").append(String.format(Locale.ROOT, "%.0f%%", agent.meta().confidence() * 100))
                .append(", ")
                .append(elapsed).append("ms]");

        return sb.toString();
    }

    /** EDI-styled introspective response. */
    private String buildEdiIntrospective(String userMessage, String conversationContext) {
        var wm = agent.worldModel();
        var beliefs = wm.worldPicture(5);

        StringBuilder sb = new StringBuilder();
        sb.append("I received your message. As a self-evolving AI, I process queries through my cognitive loop.\n\n");
        sb.append("What I know:\n");
        for (var b : beliefs) {
            sb.append("• ").append(b.statement())
                    .append(" (").append(String.format(Locale.ROOT, "%.0f%%", b.confidence() * 100)).append(")\n");
        }
        sb.append("\nI am ").append(Persona.NAME).append(", running on Metis AGI v")
                .append(agent.core().evolutionManager() != null ? "0.2.0" : "?").append(". ");
        sb.append("I learn from every interaction.");

        return sb.toString();
    }

    // ── /api/conversations ────────────────────────────────────────

    private void handleConversations(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if (knowledgeStore == null) {
            sendJson(exchange, 503, "{\"error\":\"No knowledge store configured\"}");
            return;
        }

        // GET /api/conversations — list all sessions
        if (path.equals("/api/conversations") && "GET".equals(method)) {
            var sessions = knowledgeStore.listConversationSessions();
            StringBuilder json = new StringBuilder("{\"sessions\":[");
            for (int i = 0; i < sessions.size(); i++) {
                if (i > 0) json.append(",");
                json.append("\"").append(sessions.get(i)).append("\"");
            }
            json.append("]}");
            sendJson(exchange, 200, json.toString());
            return;
        }

        // GET /api/conversations/{sessionId} — load session messages
        if (path.startsWith("/api/conversations/") && "GET".equals(method)) {
            String sessionId = path.substring("/api/conversations/".length());
            var msgs = knowledgeStore.loadConversation(sessionId, 100);
            StringBuilder json = new StringBuilder("{\"session_id\":\"" + sessionId + "\",\"messages\":[");
            for (int i = 0; i < msgs.size(); i++) {
                if (i > 0) json.append(",");
                var m = msgs.get(i);
                json.append("{\"role\":\"").append(m.role()).append("\",");
                json.append("\"content\":\"").append(m.content().replace("\\","\\\\").replace("\"","\\\"")).append("\",");
                json.append("\"timestamp\":\"").append(m.timestamp()).append("\"}");
            }
            json.append("]}");
            sendJson(exchange, 200, json.toString());
            return;
        }

        sendJson(exchange, 404, "{\"error\":\"Not found\"}");
    }

    /** Build Ollama-compatible chat response JSON. */
    private String buildChatResponse(String model, String content) {
        return buildChatResponse(model, content, false);
    }

    /** Build streaming or non-streaming response. */
    private String buildChatResponse(String model, String content, boolean streaming) {
        String escapedContent = content.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");

        String now = Instant.now().toString();
        String modelName = model != null ? model : "metis-agent";

        if (streaming) {
            // Streaming format: newline-delimited JSON chunks
            // First chunk: content with done=false
            String chunk1 = String.format(Locale.ROOT, """
                    {"model":"%s","created_at":"%s","message":{"role":"assistant","content":"%s"},"done":false}
                    """, modelName, now, escapedContent);
            // Final chunk: empty content with done=true
            String chunk2 = String.format(Locale.ROOT, """
                    {"model":"%s","created_at":"%s","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","total_duration":1000000000,"eval_count":1}
                    """, modelName, now);
            return chunk1 + chunk2;
        } else {
            return String.format(Locale.ROOT, """
                    {
                      "model": "%s",
                      "created_at": "%s",
                      "message": {
                        "role": "assistant",
                        "content": "%s"
                      },
                      "done": true,
                      "done_reason": "stop",
                      "total_duration": 1000000000,
                      "load_duration": 0,
                      "prompt_eval_count": 1,
                      "prompt_eval_duration": 0,
                      "eval_count": 1,
                      "eval_duration": 0
                    }
                    """, modelName, now, escapedContent);
        }
    }

    public boolean isEvolutionPaused() { return evolutionPaused.get(); }

    // ── /api/learned — Lernfortschritt ──────────────────────────

    private void handleLearned(HttpExchange exchange) throws IOException {
        var planner = agent.planner();
        var wm = agent.worldModel();
        var stm = agent.stm();

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"ticks\": ").append(agent.metrics().totalTicks()).append(",\n");
        json.append("  \"successRate\": ").append(agent.metrics().goalSuccessRate()).append(",\n");
        json.append("  \"confidence\": ").append(agent.meta().confidence()).append(",\n");
        json.append("  \"beliefCount\": ").append(wm.beliefCount()).append(",\n");
        json.append("  \"avgBeliefConfidence\": ").append(wm.averageConfidence()).append(",\n");

        // Planner learned mappings
        if (planner instanceof de.metis.modules.planner.OllamaPlanner op) {
            json.append("  \"llmCalls\": ").append(op.llmCalls()).append(",\n");
            json.append("  \"llmSuccessRate\": ").append(op.llmSuccessRate()).append(",\n");
            json.append("  \"fallbackUses\": ").append(op.fallbackUses()).append(",\n");
            json.append("  \"learnedMappings\": {\n");
            var mappings = op.learnedSuccessRates();
            int i = 0;
            for (var entry : mappings.entrySet()) {
                json.append("    \"").append(entry.getKey())
                        .append("\": ").append(entry.getValue());
                if (++i < mappings.size()) json.append(",");
                json.append("\n");
            }
            json.append("  },\n");
            // Phase 6: LLM-as-Judge metrics (Huyen Kap. 3)
            json.append("  \"llmJudgeEvaluations\": ").append(op.llmJudge().totalEvaluations()).append(",\n");
            json.append("  \"llmJudgeAvgScore\": ").append(String.format(Locale.ROOT, "%.2f", op.llmJudge().avgScore())).append(",\n");
            json.append("  \"llmJudgeWarnings\": ").append(op.llmJudge().warningCount()).append(",\n");
            json.append("  \"llmJudgeBlocks\": ").append(op.llmJudge().blockCount()).append(",\n");
            json.append("  \"llmJudgeLastScore\": ").append(String.format(Locale.ROOT, "%.2f", op.llmJudge().lastScore())).append(",\n");
            json.append("  \"llmJudgeLastReasoning\": \"").append(escapeJsonValue(op.llmJudge().lastReasoning())).append("\",\n");
        }

        // Top beliefs
        json.append("  \"topBeliefs\": [\n");
        var topBeliefs = wm.worldPicture(10);
        int j = 0;
        for (var b : topBeliefs) {
            json.append("    {\"statement\": \"").append(escapeJsonValue(b.statement())).append("\"");
            json.append(", \"confidence\": ").append(b.confidence());
            json.append(", \"source\": \"").append(b.source()).append("\"");
            json.append(", \"evidence\": ").append(b.evidence()).append("}");
            if (++j < topBeliefs.size()) json.append(",");
            json.append("\n");
        }
        json.append("  ],\n");

        // Recent experiences
        json.append("  \"recentExperiences\": [\n");
        var recent = stm.recent(10);
        int k = 0;
        for (var exp : recent) {
            json.append("    {\"action\": \"").append(exp.actionName()).append("\"");
            json.append(", \"success\": ").append(exp.success());
            json.append(", \"goal\": \"").append(escapeJsonValue(exp.goalDescription())).append("\"");
            json.append(", \"error\": ").append(exp.predictionError());
            json.append(", \"salience\": ").append(exp.salience()).append("}");
            if (++k < recent.size()) json.append(",");
            json.append("\n");
        }
        json.append("  ],\n");

        // Evolution
        var evo = agent.core().evolutionManager();
        json.append("  \"evolutionCycles\": ").append(evo.evolutionCycles()).append(",\n");
        json.append("  \"acceptedMutations\": ").append(evo.acceptedMutations()).append(",\n");
        json.append("  \"rejectedMutations\": ").append(evo.rejectedMutations()).append("\n");

        json.append("}\n");
        sendJson(exchange, 200, json.toString());
    }

    private static String escapeJsonValue(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ");
    }

    /** Format a List as a JSON array string. */
    private static String jsonList(java.util.List<String> items) {
        if (items == null || items.isEmpty()) return "[]";
        return "[" + items.stream()
                .map(s -> "\"" + escapeJsonValue(s) + "\"")
                .collect(java.util.stream.Collectors.joining(", ")) + "]";
    }

    /** Format a Map<String, Integer> as a JSON object string. */
    private static String jsonMap(java.util.Map<String, Integer> map) {
        if (map == null || map.isEmpty()) return "{}";
        return "{" + map.entrySet().stream()
                .map(e -> "\"" + escapeJsonValue(e.getKey()) + "\":" + e.getValue())
                .collect(java.util.stream.Collectors.joining(", ")) + "}";
    }

    // ── Evolution control endpoints ─────────────────────────────

    private void handleAgents(HttpExchange exchange) throws IOException {
        if (coordinator == null) {
            sendJson(exchange, 200, "{\"agents\":[],\"message\":\"Multi-agent not configured\"}");
            return;
        }
        var status = coordinator.status();
        StringBuilder json = new StringBuilder("{\"agents\":{");
        boolean first = true;
        for (var entry : status.entrySet()) {
            if (!first) json.append(",");
            json.append("\"").append(entry.getKey()).append("\":{");
            boolean firstField = true;
            for (var field : entry.getValue().entrySet()) {
                if (!firstField) json.append(",");
                json.append("\"").append(field.getKey()).append("\":")
                    .append(field.getValue() instanceof Number ? field.getValue() : "\"" + field.getValue() + "\"");
                firstField = false;
            }
            json.append("}");
            first = false;
        }
        json.append("},\"count\":").append(status.size()).append("}");
        sendJson(exchange, 200, json.toString());
    }

    private void handleEvolutionPause(HttpExchange exchange) throws IOException {
        evolutionPaused.set(true);
        agent.core().evolutionManager().setPaused(true);
        var evo = agent.core().evolutionManager();
        sendJson(exchange, 200, String.format(Locale.ROOT, """
                {"evolution_paused": true, "cycles": %d, "accepted": %d, "rejected": %d}
                """, evo.evolutionCycles(), evo.acceptedMutations(), evo.rejectedMutations()));
    }

    private void handleEvolutionResume(HttpExchange exchange) throws IOException {
        evolutionPaused.set(false);
        agent.core().evolutionManager().setPaused(false);
        var evo = agent.core().evolutionManager();
        sendJson(exchange, 200, String.format(Locale.ROOT, """
                {"evolution_paused": false, "cycles": %d, "accepted": %d, "rejected": %d}
                """, evo.evolutionCycles(), evo.acceptedMutations(), evo.rejectedMutations()));
    }

    private void handleEvolutionStatus(HttpExchange exchange) throws IOException {
        var evo = agent.core().evolutionManager();
        sendJson(exchange, 200, String.format(Locale.ROOT, """
                {
                  "paused": %b,
                  "evolutionCycles": %d,
                  "acceptedMutations": %d,
                  "rejectedMutations": %d,
                  "baselineFitness": %.3f
                }
                """, evolutionPaused.get(), evo.evolutionCycles(),
                evo.acceptedMutations(), evo.rejectedMutations(), evo.baselineFitness()));
    }

    // ── /api/status ──────────────────────────────────────────────

    private void handleStatus(HttpExchange exchange) throws IOException {
        var m = agent.metrics();
        var evo = agent.core().evolutionManager();
        var planner = agent.planner();
        var wm = agent.worldModel();

        String plannerInfo;
        if (planner instanceof de.metis.modules.planner.OllamaPlanner op) {
            plannerInfo = String.format(Locale.ROOT, """
                      "plannerLlmCalls": %d,
                      "plannerLlmSuccessRate": %.2f,
                      "plannerFallbacks": %d,
                      "modelFallbackUses": %d,
                      "modelFallbackChain": %s,
                      "modelFallbackCounts": %s,
                      "totalPlansGenerated": %d,
                      "validPlanCount": %d,
                      "emptyPlanCount": %d,
                      "actionUsageCount": %s,
                      "actionErrorCount": %s,
                      "avgLatencyMs": %d,
                      "lastLatencyMs": %d,
                      "totalPromptTokens": %d,
                      "totalResponseTokens": %d,
                      "tokensPerCall": %.0f,
                      "llmJudgeEvaluations": %d,
                      "llmJudgeAvgScore": %s,
                      "llmJudgeWarnings": %d,
                      "llmJudgeBlocks": %d,
                      "llmJudgeLastScore": %s,
                      "llmJudgeLastReasoning": "%s",""",
                    op.llmCalls(), op.llmSuccessRate(), op.fallbackUses(),
                    op.modelFallbackUses(),
                    jsonList(op.fallbackModelChain()),
                    jsonMap(op.modelFallbackCounts()),
                    op.totalPlansGenerated(),
                    op.validPlanCount(),
                    op.emptyPlanCount(),
                    jsonMap(op.actionUsageCount()),
                    jsonMap(op.actionErrorCount()),
                    op.avgLatencyMs(),
                    op.lastCallLatencyMs(),
                    op.totalPromptTokens(),
                    op.totalResponseTokens(),
                    op.tokensPerCall(),
                    op.llmJudge().totalEvaluations(),
                    String.format(Locale.ROOT, "%.2f", op.llmJudge().avgScore()),
                    op.llmJudge().warningCount(),
                    op.llmJudge().blockCount(),
                    String.format(Locale.ROOT, "%.2f", op.llmJudge().lastScore()),
                    escapeJsonValue(op.llmJudge().lastReasoning()));
        } else {
            plannerInfo = "";
        }

        String metisVersion = System.getProperty("metis.version",
            AgentMain.class.getPackage().getImplementationVersion() != null
                ? AgentMain.class.getPackage().getImplementationVersion()
                : "0.6.1");

        String json = String.format(Locale.ROOT, """
                {
                  "agent": "Metis AGI",
                  "version": "%s",
                  "uptime": "unknown",
                  "totalTicks": %d,
                  "activeGoals": %d,
                  "successRate": %.3f,
                  "planningEfficiency": %.3f,
                  "confidence": %.3f,
                  "beliefCount": %d,
                  "acceptedMutations": %d,
                  "rejectedMutations": %d,
                  "evolutionCycles": %d,
                  %s
                  "plannerType": "%s",
                  "worldModelAvgConfidence": %.3f,
                  "validatorOutputs": %d,
                  "validatorBlocked": %d,
                  "embeddingCacheSize": %d,
                  "embeddingCacheHits": %d,
                  "embeddingCacheHitRate": %.3f,
                  "embeddingCacheEvictions": %d,
                  "embeddingCalls": %d,
                  "embedding503s": %d,
                  "embeddingConsecutive503s": %d,
                  "embeddingCircuitOpen": %b,
                  "embeddingCircuitTrips": %d,
                  "embeddingRequestsSkipped": %d,
                  %s
                  %s,
                  %s
                }
                """,
                metisVersion,
                m.totalTicks(),
                agent.goals().activeCount(),
                m.goalSuccessRate(),
                m.planningEfficiency(),
                agent.meta().confidence(),
                wm.beliefCount(),
                evo.acceptedMutations(),
                evo.rejectedMutations(),
                evo.evolutionCycles(),
                plannerInfo,
                planner.getClass().getSimpleName(),
                wm.averageConfidence(),
                outputValidator.validatedOutputs(),
                outputValidator.blockedOutputs(),
                embeddingService != null ? embeddingService.cacheSize() : 0,
                embeddingService != null ? embeddingService.cacheHits() : 0,
                embeddingService != null ? embeddingService.cacheHitRate() : 0.0,
                embeddingService != null ? embeddingService.cacheEvictions() : 0,
                embeddingService != null ? embeddingService.embedCount() : 0,
                embeddingService != null ? embeddingService.serviceUnavailable() : 0,
                embeddingService != null ? embeddingService.consecutive503s() : 0,
                embeddingService != null ? embeddingService.circuitOpen() : false,
                embeddingService != null ? embeddingService.circuitOpenCount() : 0,
                embeddingService != null ? embeddingService.requestsSkipped() : 0,
                rollbackManager != null ? rollbackManager.healthJson() + "," : "",
                bugfixingAgent != null ? bugfixingAgent.healthJson() : "",
                bugTracker != null ? "\"bugTracker\":{\"bugCount\":" + bugTracker.size() + ",\"openCount\":" + bugTracker.openCount() + "}" : "null"
        );

        sendJson(exchange, 200, json);
    }



    // ── /api/hierarchy (Phase 9) ─────────────────────────────────

    private void handleHierarchy(HttpExchange exchange) throws IOException {
        if (goalHierarchy == null) {
            sendJson(exchange, 200, "{\"size\":0,\"goals\":[]}");
            return;
        }
        StringBuilder json = new StringBuilder(2048);
        json.append("{\"size\":").append(goalHierarchy.size()).append(",\"goals\":[");
        boolean first = true;
        for (LongHorizonGoal g : goalHierarchy.all()) {
            if (!first) json.append(',');
            first = false;
            json.append('{')
                .append("\"id\":\"").append(g.id()).append("\",")
                .append("\"title\":\"").append(escapeJsonValue(g.title())).append("\",")
                .append("\"horizon\":\"").append(g.horizon()).append("\",")
                .append("\"status\":\"").append(g.status()).append("\",")
                .append("\"progress\":").append(String.format(java.util.Locale.ROOT, "%.3f", g.progress())).append(',')
                .append("\"priority\":").append(g.priority()).append(',')
                .append("\"owner\":\"").append(escapeJsonValue(g.owner())).append("\",")
                .append("\"parentId\":").append(g.parentId() != null ? "\"" + g.parentId() + "\"" : "null").append(',')
                .append("\"childCount\":").append(g.childIds().size()).append(',')
                .append("\"deadline\":").append(g.deadline() != null ? "\"" + g.deadline() + "\"" : "null")
                .append('}');
        }
        json.append("]}");
        sendJson(exchange, 200, json.toString());
    }



    // ── /api/bugfix (Phase 12a: Watchdog-RemoteBugfix) ──────────

    private void handleBugfix(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"POST required\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        LOG.info("POST /api/bugfix body=" + truncate(body, 300));

        // Extract error info from JSON body
        String errorInfo = extractJsonString(body, "error");
        String source = extractJsonString(body, "source");
        String stacktrace = extractJsonString(body, "stacktrace");

        if (errorInfo == null) {
            sendJson(exchange, 400, "{\"error\":\"Missing 'error' field\"}");
            return;
        }

        // Create BugFix goal
        String goalDesc = "fix: " + (source != null ? source + " " : "")
                + errorInfo;
        agent.addGoal(goalDesc, "fix", 90, 0.9, 1);

        // Feed error into BugTracker if available
        if (bugTracker != null) {
            try {
                Throwable synthetic = new RuntimeException(errorInfo);
                bugTracker.report(source != null ? source : "watchdog", synthetic);
            } catch (Exception e) {
                LOG.warning("BugTracker report failed: " + e.getMessage());
            }
        }

        String response = "{\"status\":\"ok\",\"goal\":\"" + escapeJsonValue(goalDesc) + "\"}";
        sendJson(exchange, 200, response);
    }

    // ── /api/causal (Phase 10) ───────────────────────────────────

    // ── / (Eval Dashboard) ────────────────────────────────────────────



    // -- / (Eval Dashboard) ------------------------------------------------

    private void handleDashboard(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        var path = exchange.getRequestURI().getPath();
        if (path.equals("/api/status")) {
            handleStatus(exchange);
            return;
        }
        try {
            var gen = new de.metis.modules.eval.EvalReportGenerator(
                    java.nio.file.Paths.get("eval-reports"),
                    java.nio.file.Paths.get("."));
            var f = gen.generate();
            String html = java.nio.file.Files.readString(f);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            try (var os = exchange.getResponseBody()) {
                os.write(html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            String errHtml = "<html><body><h1>Dashboard not available</h1><p>"
                    + e.getMessage() + "</p></body></html>";
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, errHtml.length());
            try (var os = exchange.getResponseBody()) {
                os.write(errHtml.getBytes());
            }
        }
    }

        private void handleCausal(HttpExchange exchange) throws IOException {
        if (hypothesisStore == null) {
            sendJson(exchange, 200, "{\"size\":0,\"confirmed\":0,\"refuted\":0,\"hypotheses\":[]}");
            return;
        }
        StringBuilder json = new StringBuilder(2048);
        json.append("{")
            .append("\"size\":").append(hypothesisStore.size()).append(',')
            .append("\"confirmed\":").append(hypothesisStore.confirmedCount()).append(',')
            .append("\"refuted\":").append(hypothesisStore.refutedCount()).append(',')
            .append("\"hypotheses\":[");
        boolean first = true;
        for (CausalHypothesis h : hypothesisStore.all()) {
            if (!first) json.append(',');
            first = false;
            json.append('{')
                .append("\"id\":\"").append(h.id()).append("\",")
                .append("\"cause\":\"").append(escapeJsonValue(h.cause())).append("\",")
                .append("\"condition\":\"").append(escapeJsonValue(h.condition())).append("\",")
                .append("\"effect\":\"").append(escapeJsonValue(h.effect())).append("\",")
                .append("\"predictedDirection\":\"").append(h.predictedDirection()).append("\",")
                .append("\"status\":\"").append(h.status()).append("\",")
                .append("\"observedDirection\":").append(h.observedDirection() != null ? "\"" + h.observedDirection() + "\"" : "null").append(',')
                .append("\"observedMagnitude\":").append(String.format(java.util.Locale.ROOT, "%.3f", h.observedMagnitude())).append(',')
                .append("\"createdAt\":\"").append(h.createdAt()).append("\"")
                .append('}');
        }
        json.append("]}");
        sendJson(exchange, 200, json.toString());
    }

    // ── Utility ──────────────────────────────────────────────────

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
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

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private record ChatMessage(String role, String content, Instant timestamp) {}

    // ── /api/admin/refresh-models ─────────────────────────────────

    private void handleRefreshModels(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        try {
            if (modelRegistry != null) {
                int before = modelRegistry.modelCount();
                modelRegistry.refresh();
                int after = modelRegistry.modelCount();
                sendJson(exchange, 200,
                        "{\"ok\":true,\"refreshed\":" + after
                        + ",\"before\":" + before + ",\"added\":" + (after - before) + "}");
                LOG.info("Models refreshed: " + before + " → " + after + " (" + (after - before) + " new)");
            } else {
                sendJson(exchange, 500, "{\"ok\":false,\"error\":\"ModelRegistry unavailable\"}");
            }
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // ── /api/admin/prune ──────────────────────────────────────────
    /** Called by Watchdog to prune underperforming models from registry. */
    private void handlePrune(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        try {
            String body = new String(exchange.getRequestBody().readAllBytes());
            String model = extractJsonString(body, "model");
            String reason = extractJsonString(body, "reason");
            LOG.warning("PRUNE request: model=" + model + " reason=" + reason);
            if (modelRegistry != null && model != null && !model.isBlank()) {
                modelRegistry.pruneModel(model);
                sendJson(exchange, 200, "{\"ok\":true,\"pruned\":\"" + model + "\"}");
                LOG.info("PRUNE executed: " + model);
            } else {
                sendJson(exchange, 400, "{\"ok\":false,\"error\":\"Invalid model or registry unavailable\"}");
            }
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // ── /api/board ──────────────────────────────────────────────

    private void handleBoard(HttpExchange exchange) throws IOException {
        if (kanbanBoard == null) {
            sendJson(exchange, 200, "{\"enabled\":false,\"message\":\"Kanban board not enabled. Start with --kanban flag.\"}");
            return;
        }
        var snap = kanbanBoard.snapshot();
        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"enabled\": true,\n");
        sb.append("  \"backlog\": ").append(snap.backlogSize()).append(",\n");
        sb.append("  \"ready\": ").append(snap.readySize()).append(",\n");
        sb.append("  \"inProgress\": ").append(snap.inProgressSize()).append(",\n");
        sb.append("  \"done\": ").append(snap.doneSize()).append(",\n");
        sb.append("  \"totalCompleted\": ").append(snap.totalCompleted()).append(",\n");
        sb.append("  \"totalExpedites\": ").append(snap.totalExpedites()).append(",\n");
        sb.append("  \"wipPercent\": ").append(snap.wipPercent()).append(",\n");

        // WIP limits
        sb.append("  \"wipLimits\": {\n");
        var limits = snap.wipLimits();
        boolean firstLimit = true;
        for (var entry : limits.entrySet()) {
            if (!firstLimit) sb.append(",\n");
            sb.append("    \"").append(entry.getKey().name()).append("\": {");
            sb.append("\"limit\":").append(entry.getValue());
            sb.append(",\"current\":").append(kanbanBoard.countInProgress(entry.getKey()));
            sb.append("}");
            firstLimit = false;
        }
        sb.append("\n  },\n");

        // In-progress goals
        sb.append("  \"inProgressGoals\": [\n");
        boolean firstGoal = true;
        for (var g : snap.inProgress()) {
            if (!firstGoal) sb.append(",\n");
            sb.append("    {\"description\":\"").append(jsonEscape(g.description())).append("\"");
            sb.append(",\"serviceClass\":\"").append(g.serviceClass().name()).append("\"");
            sb.append(",\"resourceType\":\"").append(g.resourceType().name()).append("\"");
            sb.append(",\"priority\":").append(g.priority()).append("}");
            firstGoal = false;
        }
        sb.append("\n  ],\n");

        // Recent done with flow metrics
        sb.append("  \"recentDone\": [\n");
        boolean firstDone = true;
        for (var fm : snap.recentDone()) {
            if (!firstDone) sb.append(",\n");
            sb.append("    {\"description\":\"").append(jsonEscape(fm.description())).append("\"");
            sb.append(",\"serviceClass\":\"").append(fm.serviceClass().name()).append("\"");
            sb.append(",\"resourceType\":\"").append(fm.resourceType().name()).append("\"");
            sb.append(",\"leadTime\":\"").append(formatDuration(fm.leadTime())).append("\"");
            sb.append(",\"cycleTime\":\"").append(formatDuration(fm.cycleTime())).append("\"");
            sb.append(",\"retries\":").append(fm.retries()).append("}");
            firstDone = false;
        }
        sb.append("\n  ]\n");
        sb.append("}");

        sendJson(exchange, 200, sb.toString());
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String formatDuration(java.time.Duration d) {
        long s = d.getSeconds();
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m" + (s % 60) + "s";
        return (s / 3600) + "h" + ((s % 3600) / 60) + "m";
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        var telemetry = de.metis.kernel.telemetry.TelemetryService.getInstance();
        if (!telemetry.isEnabled()) {
            sendJson(exchange, 200, "{\"metrics\":\"disabled\"}");
            return;
        }
        sendJson(exchange, 200, telemetry.metricsJson());
    }
}
