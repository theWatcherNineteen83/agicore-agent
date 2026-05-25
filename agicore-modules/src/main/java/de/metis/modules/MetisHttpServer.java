package de.metis.modules;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Ollama-compatible HTTP API so Metis appears as a model in OpenWebUI.
 * <p>
 * Endpoints:
 * <ul>
 *   <li>{@code GET /api/tags} — returns "metis-agent" as available model</li>
 *   <li>{@code POST /api/chat} — accepts Ollama chat format, processes as goal,
 *       returns Metis response with reasoning trace</li>
 *   <li>{@code GET /api/status} — Metis-specific: agent metrics, beliefs, evolution state</li>
 * </ul>
 * <p>
 * Usage in OpenWebUI: add a new Ollama connection pointing to {@code http://kali:11735}.
 * Then select "metis-agent" as the model.
 */
public class MetisHttpServer {

    private static final Logger LOG = Logger.getLogger(MetisHttpServer.class.getName());

    private final HttpServer server;
    private final Agent agent;
    private final int port;
    private final List<ChatMessage> conversationHistory = new ArrayList<>();
    private static final int MAX_HISTORY = 50;
    private final AtomicBoolean evolutionPaused = new AtomicBoolean(false);

    public MetisHttpServer(Agent agent, int port) throws IOException {
        this.agent = agent;
        this.port = port;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/api/tags", this::handleTags);
        server.createContext("/api/chat", this::handleChat);
        server.createContext("/api/show", this::handleShow);
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/evolution/pause", this::handleEvolutionPause);
        server.createContext("/api/evolution/resume", this::handleEvolutionResume);
        server.createContext("/api/evolution/status", this::handleEvolutionStatus);
        server.createContext("/api/learned", this::handleLearned);
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

        if (userMessage == null || userMessage.isBlank()) {
            LOG.warning("No user message in request. Body (first 500 chars): " + truncate(body, 500));
            sendJson(exchange, 400, "{\"error\":\"No user message found\"}");
            return;
        }

        LOG.info("Chat message: \"" + truncate(userMessage, 80) + "\"");

        // Store in conversation history
        conversationHistory.add(new ChatMessage("user", userMessage, Instant.now()));
        while (conversationHistory.size() > MAX_HISTORY) {
            conversationHistory.removeFirst();
        }

        // Process as a goal for Metis
        String response;
        long startMs = System.currentTimeMillis();

        try {
            // Add as goal to agent
            agent.addGoal(userMessage, "chat", 90, 0.95, 1);

            // Run one cognitive tick to process the goal
            var result = agent.core().tick();

            if (result != null && result.success()) {
                response = buildResponse(userMessage, result.body(), startMs);
            } else if (result != null) {
                response = buildIntrospectiveResponse(userMessage);
            } else {
                response = buildIntrospectiveResponse(userMessage);
            }
        } catch (Exception e) {
            response = "Error processing: " + e.getMessage();
        }

        conversationHistory.add(new ChatMessage("assistant", response, Instant.now()));

        // Build Ollama-compatible chat response
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

    /** Build a thoughtful response using Metis's internal state. */
    private String buildResponse(String userMessage, String actionOutput, long startMs) {
        var wm = agent.worldModel();
        var beliefs = wm.worldPicture(3);

        StringBuilder sb = new StringBuilder();
        sb.append("Ich habe deine Anfrage durch meinen kognitiven Zyklus verarbeitet.\n\n");

        if (actionOutput != null && !actionOutput.isBlank()) {
            String trimmed = actionOutput.length() > 500
                    ? actionOutput.substring(0, 500) + "..." : actionOutput;
            sb.append("Aktionsergebnis:\n```\n").append(trimmed).append("\n```\n\n");
        }

        // Add relevant beliefs
        if (!beliefs.isEmpty()) {
            sb.append("Relevantes Wissen:\n");
            for (var b : beliefs) {
                sb.append("• ").append(b.statement())
                        .append(" (").append(String.format("%.0f%%", b.confidence() * 100)).append(")\n");
            }
            sb.append("\n");
        }

        // Agent state
        sb.append("Mein Zustand: confidence=").append(String.format("%.0f%%", agent.meta().confidence() * 100))
                .append(", beliefs=").append(wm.beliefCount())
                .append(", mutations=").append(agent.core().evolutionManager().acceptedMutations())
                .append("\n");

        long elapsed = System.currentTimeMillis() - startMs;
        sb.append("(verarbeitet in ").append(elapsed).append("ms)");

        return sb.toString();
    }

    /** Fallback: no action matched, provide introspective response. */
    private String buildIntrospectiveResponse(String userMessage) {
        var wm = agent.worldModel();
        var beliefs = wm.worldPicture(5);

        StringBuilder sb = new StringBuilder();
        sb.append("Ich habe deine Nachricht erhalten, habe aber keine direkte Aktion dafür.\n\n");

        sb.append("Was ich weiß:\n");
        for (var b : beliefs) {
            sb.append("• ").append(b.statement())
                    .append(" (").append(String.format("%.0f%%", b.confidence() * 100)).append(")\n");
        }

        sb.append("\nIch bin eine selbst-evolvierende AGI (Metis). Ich lerne aus Interaktionen.\n");
        sb.append("Mein Planer nutzt Ollama (")
                .append(agent.planner() instanceof de.metis.modules.planner.OllamaPlanner ? "LLM-powered" : "keyword-based")
                .append(") und habe  ")
                .append(agent.metrics().totalTicks()).append(" kognitive Ticks.");

        return sb.toString();
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
            String chunk1 = String.format("""
                    {"model":"%s","created_at":"%s","message":{"role":"assistant","content":"%s"},"done":false}
                    """, modelName, now, escapedContent);
            // Final chunk: empty content with done=true
            String chunk2 = String.format("""
                    {"model":"%s","created_at":"%s","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","total_duration":1000000000,"eval_count":1}
                    """, modelName, now);
            return chunk1 + chunk2;
        } else {
            return String.format("""
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

    private void handleEvolutionPause(HttpExchange exchange) throws IOException {
        evolutionPaused.set(true);
        agent.core().evolutionManager().setPaused(true);
        var evo = agent.core().evolutionManager();
        sendJson(exchange, 200, String.format("""
                {"evolution_paused": true, "cycles": %d, "accepted": %d, "rejected": %d}
                """, evo.evolutionCycles(), evo.acceptedMutations(), evo.rejectedMutations()));
    }

    private void handleEvolutionResume(HttpExchange exchange) throws IOException {
        evolutionPaused.set(false);
        agent.core().evolutionManager().setPaused(false);
        var evo = agent.core().evolutionManager();
        sendJson(exchange, 200, String.format("""
                {"evolution_paused": false, "cycles": %d, "accepted": %d, "rejected": %d}
                """, evo.evolutionCycles(), evo.acceptedMutations(), evo.rejectedMutations()));
    }

    private void handleEvolutionStatus(HttpExchange exchange) throws IOException {
        var evo = agent.core().evolutionManager();
        sendJson(exchange, 200, String.format("""
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
            plannerInfo = String.format("""
                      "plannerLlmCalls": %d,
                      "plannerLlmSuccessRate": %.2f,
                      "plannerFallbacks": %d,
                      "modelFallbackUses": %d,
                      "modelFallbackChain": %s,
                      "modelFallbackCounts": %s,""",
                    op.llmCalls(), op.llmSuccessRate(), op.fallbackUses(),
                    op.modelFallbackUses(),
                    jsonList(op.fallbackModelChain()),
                    jsonMap(op.modelFallbackCounts()));
        } else {
            plannerInfo = "";
        }

        String json = String.format("""
                {
                  "agent": "Metis AGI",
                  "version": "0.2.0-evolution",
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
                  "worldModelAvgConfidence": %.3f
                }
                """,
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
                wm.averageConfidence()
        );

        sendJson(exchange, 200, json);
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
}
