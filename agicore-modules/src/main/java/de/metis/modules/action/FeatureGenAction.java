package de.metis.modules.action;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;
import de.metis.kernel.goal.Goal;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

public class FeatureGenAction implements Action {
    private static final Logger LOG = Logger.getLogger(FeatureGenAction.class.getName());
    private final String ollamaUrl;
    private final String model;
    private final String projectDir;
    private Goal currentGoal = null;

    public FeatureGenAction(String ollamaUrl, String model, String projectDir) {
        this.ollamaUrl = ollamaUrl;
        this.model = model;
        this.projectDir = projectDir;
    }

    @Override public String name() { return "feature-gen"; }
    public void setCurrentGoal(Goal g) { this.currentGoal = g; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            String desc = currentGoal != null ? currentGoal.description() : "";
            if (desc.isBlank()) {
                return ActionResult.fail(name(), "No feature goal set", start);
            }
            String target = deriveTarget(desc);
            String fixCode = generateFix(desc, target);
            if (fixCode == null || fixCode.isBlank()) {
                return ActionResult.fail(name(), "Ollama returned empty fix", start);
            }
            Path fixDir = Path.of(projectDir, ".feature-gen");
            Files.createDirectories(fixDir);
            String safeName = desc.replaceAll("[^a-zA-Z0-9]", "_");
            if (safeName.length() > 60) safeName = safeName.substring(0, 60);
            Files.writeString(fixDir.resolve(safeName + ".java"), fixCode);
            boolean compiled = runMvnCompile();
            String status = compiled ? "COMPILE_OK" : "COMPILE_FAILED";
            String summary = String.format("FeatureGen: %s -> %s (target=%s)", desc, status, target);
            LOG.info(summary);
            return compiled
                    ? ActionResult.ok(name(), summary, start)
                    : ActionResult.fail(name(), "Feature did not compile: " + summary, start);
        } catch (Exception e) {
            LOG.warning("FeatureGenAction failed: " + e.getMessage());
            return ActionResult.fail(name(), "FeatureGenAction error: " + e.getMessage(), start);
        }
    }

    private String deriveTarget(String desc) {
        String lc = desc.toLowerCase();
        if (lc.contains("planning")) return "modules/planner/OllamaPlanner.java";
        if (lc.contains("success") || lc.contains("goal")) return "kernel/goal/GoalManager.java";
        if (lc.contains("confidence") || lc.contains("meta")) return "kernel/meta/MetaCognition.java";
        if (lc.contains("error") || lc.contains("bug")) return "kernel/self/BugTracker.java";
        if (lc.contains("knowledge") || lc.contains("belief")) return "modules/knowledge/WikipediaKnowledgeService.java";
        return "modules/AgentMain.java";
    }

    @SuppressWarnings("unchecked")
    private String generateFix(String desc, String target) throws Exception {
        String prompt = "Generate a Java improvement for Metis AGI. Goal: " + desc + ". Target: " + target;
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var requestNode = mapper.createObjectNode();
        requestNode.put("model", model);
        requestNode.put("prompt", prompt);
        requestNode.put("stream", false);
        var opts = mapper.createObjectNode();
        opts.put("temperature", 0.3);
        opts.put("num_predict", 1024);
        requestNode.set("options", opts);

        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestNode)))
                .timeout(Duration.ofSeconds(60)).build();

        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;
        return mapper.readTree(resp.body()).path("response").asText("").trim();
    }

    private boolean runMvnCompile() {
        try {
            ProcessBuilder pb = new ProcessBuilder("mvn", "compile", "-q", "-pl", "agicore-modules", "-am");
            pb.directory(Path.of(projectDir).toFile());
            pb.redirectErrorStream(true);
            return pb.start().waitFor() == 0;
        } catch (Exception e) {
            LOG.warning("Compile check failed: " + e.getMessage());
            return false;
        }
    }
}
