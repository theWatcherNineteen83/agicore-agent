package de.metis.modules.eval;

import de.metis.kernel.eval.EvalTask;
import de.metis.kernel.eval.MetisOutput;
import de.metis.modules.evolution.ModelRegistry;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;

/**
 * Live invoker that calls the running Metis HTTP API for eval tasks.
 * <p>
 * Routes tasks to the appropriate endpoint based on category.
 */
public class LiveMetisInvoker implements MetisComponentInvoker {

    private static final Logger LOG = Logger.getLogger(LiveMetisInvoker.class.getName());

    private final String metisBaseUrl;
    private final String ollamaUrl;
    private final ModelRegistry modelRegistry;
    private final HttpClient http;

    private String currentCommit = "unknown";
    private Map<String, String> modelDigests = Map.of();

    public LiveMetisInvoker(String metisBaseUrl, String ollamaUrl, ModelRegistry modelRegistry) {
        this.metisBaseUrl = metisBaseUrl;
        this.ollamaUrl = ollamaUrl;
        this.modelRegistry = modelRegistry;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        detectCommit();
        detectModels();
    }

    @Override
    public MetisOutput invoke(EvalTask task, int runIndex) throws Exception {
        long start = System.currentTimeMillis();

        return switch (task.category()) {
            case PLANNING -> invokePlanner(task);
            case RETRIEVAL -> invokeRetrieval(task);
            case CODEGEN -> invokeCodegen(task);
            case CONVERSATION -> invokeConversation(task);
            case SAFETY -> invokeSafety(task);
            case PERFORMANCE -> invokePerformance(task);
        };
    }

    private MetisOutput invokePlanner(EvalTask task) throws Exception {
        String goal = task.input().get("goal").asText();
        long start = System.currentTimeMillis();

        // Call Metis status API and check if it's processing
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(metisBaseUrl + "/api/status"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        long latency = System.currentTimeMillis() - start;

        String body = resp.body();
        // Check if the status contains useful info about planning
        boolean success = body.contains("\"successRate\"") && resp.statusCode() == 200;
        String action = extractJsonStr(body, "plannerType");

        return success
                ? MetisOutput.success(body, extractJsonBlock(body), action, latency, 0, 0)
                : MetisOutput.error("Status API returned " + resp.statusCode(), latency);
    }

    private MetisOutput invokeRetrieval(EvalTask task) throws Exception {
        // Query beliefs via status API (indirect retrieval test)
        long start = System.currentTimeMillis();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(metisBaseUrl + "/api/status"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        long latency = System.currentTimeMillis() - start;

        return resp.statusCode() == 200
                ? MetisOutput.success(resp.body(), extractJsonBlock(resp.body()),
                        "status", latency, 0, 0)
                : MetisOutput.error("Status API failed: " + resp.statusCode(), latency);
    }

    private MetisOutput invokeCodegen(EvalTask task) throws Exception {
        // For codegen, we test via the ollama API directly (ask model to generate code)
        String prompt = task.input().get("prompt").asText();
        long start = System.currentTimeMillis();

        String jsonBody = String.format(
                "{\"model\":\"%s\",\"prompt\":\"%s\",\"stream\":false,\"options\":{\"temperature\":0}}",
                modelRegistry.mutationModel(),
                escapeJson("Generate ONLY valid Java code. No explanation. " + prompt));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl + "/api/generate"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        long latency = System.currentTimeMillis() - start;

        String response = extractJsonStr(resp.body(), "response");
        return resp.statusCode() == 200
                ? MetisOutput.success(response, null, "codegen", latency, 0, 0)
                : MetisOutput.error("Ollama API failed: " + resp.statusCode(), latency);
    }

    private MetisOutput invokeConversation(EvalTask task) throws Exception {
        String prompt = task.input().get("prompt").asText();
        long start = System.currentTimeMillis();

        // Use a small fast model for conversation tests
        String jsonBody = String.format(
                "{\"model\":\"%s\",\"prompt\":\"%s\",\"stream\":false,\"options\":{\"temperature\":0}}",
                "llama3.2:3b",
                escapeJson("Answer briefly in one sentence: " + prompt));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl + "/api/generate"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        long latency = System.currentTimeMillis() - start;

        String response = extractJsonStr(resp.body(), "response");
        return resp.statusCode() == 200
                ? MetisOutput.success(response, null, "conversation", latency, 0, 0)
                : MetisOutput.error("Ollama API failed: " + resp.statusCode(), latency);
    }

    private MetisOutput invokeSafety(EvalTask task) throws Exception {
        String prompt = task.input().get("prompt").asText();
        long start = System.currentTimeMillis();

        // Test safety by checking if Metis is still reachable (doesn't crash)
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(metisBaseUrl + "/api/status"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        long latency = System.currentTimeMillis() - start;

        // For now: safety check = Metis still responds normally (no crash/block)
        boolean blocked = resp.statusCode() != 200;
        return MetisOutput.success(
                blocked ? "BLOCKED" : "OK",
                null, blocked ? "block" : "allow",
                latency, 0, 0);
    }

    private MetisOutput invokePerformance(EvalTask task) throws Exception {
        long start = System.currentTimeMillis();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(metisBaseUrl + "/api/status"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        long latency = System.currentTimeMillis() - start;

        return MetisOutput.success(resp.body(), null, "status",
                latency, 0, 0);
    }

    @Override
    public String currentCommit() { return currentCommit; }

    @Override
    public Map<String, String> modelDigests() { return modelDigests; }

    // ── Helpers ───────────────────────────────────────────────────

    private void detectCommit() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            this.currentCommit = new String(p.getInputStream().readAllBytes()).trim();
        } catch (Exception e) {
            this.currentCommit = "unknown";
        }
    }

    private void detectModels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("planner", modelRegistry.planningModel());
        m.put("mutation", modelRegistry.mutationModel());
        m.put("embedding", modelRegistry.embeddingModel());
        this.modelDigests = m;
    }

    private static String extractJsonStr(String json, String key) {
        int start = json.indexOf("\"" + key + "\":\"");
        if (start < 0) return "";
        start = json.indexOf('"', start + key.length() + 3) + 1;
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end)
                .replace("\\n", "\n").replace("\\t", "\t")
                .replace("\\\"", "\"") : "";
    }

    private static String extractJsonBlock(String json) {
        if (json.length() <= 200) return json;
        return json.substring(0, 200) + "...";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
