package de.metis.modules.action;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;
import de.metis.kernel.goal.Goal;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Phase 12a — Self-Fix Action: generate compile fixes via Ollama.
 */
public class SelfFixAction implements Action {

    private static final Logger LOG = Logger.getLogger(SelfFixAction.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String ollamaUrl;
    private final String model;
    private final String projectDir;
    private Goal currentBugGoal = null;

    public SelfFixAction(String ollamaUrl, String model, String projectDir) {
        this.ollamaUrl = ollamaUrl;
        this.model = model;
        this.projectDir = projectDir;
    }

    @Override
    public String name() { return "self-fix"; }

    public void setCurrentBugGoal(Goal g) { this.currentBugGoal = g; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            String desc = currentBugGoal != null ? currentBugGoal.description() : "";
            if (desc.isBlank()) {
                return ActionResult.fail(name(),
                        "No BugFix goal set. Use setCurrentBugGoal() first.", start);
            }

            String[] parts = desc.split("\\s+");
            String classInfo = parts.length > 1 ? parts[1] : "unknown";
            String className = classInfo.contains(".")
                    ? classInfo.substring(0, classInfo.indexOf('.'))
                    : classInfo;

            String sourceFile = findSourceFile(className);
            if (sourceFile == null) {
                return ActionResult.fail(name(),
                        "Source file not found for: " + className, start);
            }

            String sourceCode = Files.readString(Path.of(sourceFile));
            if (sourceCode.length() > 8000) {
                sourceCode = sourceCode.substring(0, 8000) + "\n// ... truncated";
            }

            String fixSuggestion = suggestFix(sourceCode, desc);
            if (fixSuggestion == null || fixSuggestion.isBlank()) {
                return ActionResult.fail(name(),
                        "Ollama returned empty fix suggestion", start);
            }

            Path fixDir = Path.of(projectDir, ".self-fix");
            Files.createDirectories(fixDir);
            Files.writeString(fixDir.resolve(className + ".fix.txt"), fixSuggestion);

            boolean compiled = runMvnCompile();
            String status = compiled ? "COMPILE_OK" : "COMPILE_FAILED";
            String summary = String.format("SelfFix: %s -> %s (file=%s)", className, status, sourceFile);
            LOG.info(summary);

            return compiled
                    ? ActionResult.ok(name(), summary, start)
                    : ActionResult.fail(name(), "Fix did not compile: " + summary, start);

        } catch (Exception e) {
            LOG.warning("SelfFixAction failed: " + e.getMessage());
            return ActionResult.fail(name(), "SelfFixAction error: " + e.getMessage(), start);
        }
    }

    private String findSourceFile(String className) {
        String[] dirs = {
            projectDir + "/agicore-modules/src/main/java/",
            projectDir + "/agicore-kernel/src/main/java/",
            projectDir + "/agicore-watchdog/src/main/java/"
        };
        for (String dir : dirs) {
            try {
                Path base = Path.of(dir);
                if (!Files.exists(base)) continue;
                try (var stream = Files.walk(base, 20)) {
                    var match = stream
                            .filter(p -> p.toString().endsWith("/" + className + ".java")
                                    || p.toString().endsWith("\\" + className + ".java"))
                            .findFirst();
                    if (match.isPresent()) return match.get().toString();
                }
            } catch (IOException e) {
                LOG.fine("Search failed in " + dir + ": " + e.getMessage());
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String suggestFix(String sourceCode, String errorInfo) throws Exception {
        String prompt = String.format(
                "Fix the Java bug: %s%n%n```java%n%s%n```%nReturn ONLY fixed code, no explanation.",
                escapeJson(errorInfo), escapeJson(sourceCode));

        var requestNode = MAPPER.createObjectNode();
        requestNode.put("model", model);
        requestNode.put("prompt", prompt);
        requestNode.put("stream", false);
        var opts = MAPPER.createObjectNode();
        opts.put("temperature", 0.3);
        opts.put("num_predict", 2048);
        requestNode.set("options", opts);

        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(requestNode)))
                .timeout(Duration.ofSeconds(60))
                .build();

        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;

        var json = MAPPER.readTree(resp.body());
        String response = json.path("response").asText("");

        // Extract code block if present
        var m = Pattern.compile("```(?:java)?\\s*\\n(.+?)\\n```", Pattern.DOTALL).matcher(response);
        return m.find() ? m.group(1).trim() : response.trim();
    }

    private boolean runMvnCompile() {
        try {
            ProcessBuilder pb = new ProcessBuilder("mvn", "compile", "-q", "-pl", "agicore-modules", "-am");
            pb.directory(Path.of(projectDir).toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            if (code != 0) {
                String snippet = out.length() > 500 ? out.substring(0, 500) : out;
                LOG.info("Compile check failed: " + snippet);
            }
            return code == 0;
        } catch (Exception e) {
            LOG.warning("Compile check failed: " + e.getMessage());
            return false;
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
