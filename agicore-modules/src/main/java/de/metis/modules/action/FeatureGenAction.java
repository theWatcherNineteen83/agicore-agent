package de.metis.modules.action;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;
import de.metis.kernel.action.GoalAwareAction;
import de.metis.kernel.goal.Goal;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Code-generating action: turns a STRATEGIC goal ("Baue X — ... package Y")
 * into a real Java source file in the project tree, then compile-checks it.
 * <p>
 * The {@link de.metis.kernel.action.ActionExecutor} injects the current goal
 * via {@link GoalAwareAction#setCurrentGoal(Goal)} before execution, so the
 * action always knows what to build (fix: previously the goal was never set
 * and the action always failed with "No feature goal set").
 */
public class FeatureGenAction implements Action, GoalAwareAction {
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
    @Override public void setCurrentGoal(Goal g) { this.currentGoal = g; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            String desc = currentGoal != null ? currentGoal.description() : "";
            if (desc.isBlank()) {
                return ActionResult.fail(name(), "No feature goal set", start);
            }
            String packageName = derivePackage(desc);
            String className = deriveClassName(desc, packageName);
            String targetPath = resolveTargetPath(packageName, className);
            String fixCode = generateFix(desc, packageName, className);
            if (fixCode == null || fixCode.isBlank()) {
                return ActionResult.fail(name(), "Ollama returned empty code", start);
            }
            Path outFile = Path.of(projectDir, targetPath);
            Files.createDirectories(outFile.getParent());
            Files.writeString(outFile, fixCode);
            boolean compiled = runMvnCompile();
            String status = compiled ? "COMPILE_OK" : "COMPILE_FAILED";
            String summary = String.format("FeatureGen: %s -> %s (target=%s, %d bytes)",
                    desc, status, targetPath, fixCode.length());
            LOG.info(summary);
            if (compiled) {
                // Seed a belief so Metis knows the artifact exists
                return ActionResult.ok(name(), summary, start);
            }
            return ActionResult.fail(name(), "Feature did not compile: " + summary, start);
        } catch (Exception e) {
            LOG.warning("FeatureGenAction failed: " + e.getMessage());
            return ActionResult.fail(name(), "FeatureGenAction error: " + e.getMessage(), start);
        }
    }

    /** Extract package from goal text: "package de.metis.modules.selfrefactor" or "…modules.text". */
    private String derivePackage(String desc) {
        int idx = desc.toLowerCase().indexOf("package ");
        if (idx >= 0) {
            String rest = desc.substring(idx + "package ".length()).trim();
            StringBuilder pkg = new StringBuilder();
            for (char c : rest.toCharArray()) {
                if (Character.isLetterOrDigit(c) || c == '.') pkg.append(c);
                else break;
            }
            String p = pkg.toString().trim();
            if (p.length() > 3 && p.contains(".")) return p;
        }
        return "de.metis.modules.selfrefactor";
    }

    /** Derive class name from goal title ("Baue RoadmapReader — …" → RoadmapReader). */
    private String deriveClassName(String desc, String pkg) {
        String lc = desc.toLowerCase();
        int baueIdx = lc.indexOf("baue ");
        if (baueIdx >= 0) {
            String after = desc.substring(baueIdx + 5).trim();
            StringBuilder name = new StringBuilder();
            for (char c : after.toCharArray()) {
                if (Character.isLetterOrDigit(c)) name.append(c);
                else break;
            }
            if (name.length() >= 2) return name.toString();
        }
        // Fallback: last package segment capitalized
        String[] parts = pkg.split("\\.");
        String last = parts[parts.length - 1];
        return Character.toUpperCase(last.charAt(0)) + last.substring(1);
    }

    /** Map package to source path inside the metis-build tree. */
    private String resolveTargetPath(String pkg, String className) {
        String pkgPath = pkg.replace('.', '/');
        return "agicore-modules/src/main/java/" + pkgPath + "/" + className + ".java";
    }

    @SuppressWarnings("unchecked")
    private String generateFix(String desc, String pkg, String className) throws Exception {
        String prompt = """
                You are a senior Java engineer extending the Metis AGI codebase.
                Generate a SINGLE complete Java source file (no markdown fences, no commentary).

                Goal: %s
                Package: %s
                Class: %s

                Requirements:
                - package %s;
                - public class %s with a meaningful, working implementation matching the goal.
                - Include a private static final java.util.logging.Logger LOG.
                - No external dependencies beyond the JDK (java.util, java.nio, java.time, java.util.logging).
                - Compile-clean Java 21.
                """.formatted(desc, pkg, className, pkg, className);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var requestNode = mapper.createObjectNode();
        requestNode.put("model", model);
        requestNode.put("prompt", prompt);
        requestNode.put("stream", false);
        requestNode.put("temperature", 0.3);
        requestNode.put("num_predict", 2048);
        requestNode.set("options", mapper.createObjectNode());

        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestNode)))
                .timeout(Duration.ofSeconds(120)).build();

        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;
        String code = mapper.readTree(resp.body()).path("response").asText("").trim();
        // Strip markdown fences if the model wrapped the code
        if (code.startsWith("```")) {
            int firstNl = code.indexOf('\n');
            if (firstNl > 0) code = code.substring(firstNl + 1);
            if (code.endsWith("```")) code = code.substring(0, code.length() - 3);
            code = code.trim();
        }
        return code;
    }

    private boolean runMvnCompile() {
        try {
            ProcessBuilder pb = new ProcessBuilder("mvn", "compile", "-q", "-pl", "agicore-modules", "-am");
            pb.directory(Path.of(projectDir).toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean ok = p.waitFor(180, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
            if (!ok) {
                String out = new String(p.getInputStream().readAllBytes());
                LOG.warning("Compile check failed: " + (out.length() > 500 ? out.substring(0, 500) : out));
            }
            return ok;
        } catch (Exception e) {
            LOG.warning("Compile check failed: " + e.getMessage());
            return false;
        }
    }
}
