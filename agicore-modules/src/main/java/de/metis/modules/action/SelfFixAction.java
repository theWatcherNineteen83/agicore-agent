package de.metis.modules.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;
import de.metis.kernel.goal.Goal;
import de.metis.modules.evolution.FeatureBranchManager;
import de.metis.modules.evolution.RiskGate;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

public class SelfFixAction implements Action {

    private static final Logger LOG = Logger.getLogger(SelfFixAction.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String ollamaUrl;
    private final String model;
    private final String projectDir;
    private RiskGate riskGate;
    private FeatureBranchManager branchManager;
    private Goal currentBugGoal = null;

    public SelfFixAction(String ollamaUrl, String model, String projectDir) {
        this.ollamaUrl = ollamaUrl;
        this.model = model;
        this.projectDir = projectDir;
    }

    public void setRiskGate(RiskGate gate) { this.riskGate = gate; }
    public void setBranchManager(FeatureBranchManager mgr) { this.branchManager = mgr; }
    private CompileRepairLoop compileRepair;

    public void setCompileRepair(CompileRepairLoop cr) { this.compileRepair = cr; }

    @Override
    public String name() { return "self-fix"; }

    public void setCurrentBugGoal(Goal g) { this.currentBugGoal = g; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            String desc = currentBugGoal != null ? currentBugGoal.description() : "";
            if (desc.isBlank()) {
                return ActionResult.fail(name(), "No BugFix goal set", start);
            }

            String[] parts = desc.split(" in ");
            String classInfo = parts.length > 1 ? parts[1] : "unknown";
            String className = classInfo.contains(".")
                    ? classInfo.substring(0, classInfo.indexOf('.'))
                    : classInfo;

            String sourceFile = findSourceFile(className);
            if (sourceFile == null) {
                return ActionResult.fail(name(), "Source not found: " + className, start);
            }
            // Derive relative path for RiskGate (e.g., "kernel/core/AgentCoreLoop.java")
            String relPath = deriveRelativePath(sourceFile);

            String error = parts[0].trim();
            String fixSuggestion = generateFix(className, sourceFile, error);
            if (fixSuggestion == null || fixSuggestion.isBlank()) {
                return ActionResult.fail(name(), "Ollama returned empty fix", start);
            }

            // ── RiskGate check ─────────────────────────────────────
            boolean prRequired = false;
            if (riskGate != null) {
                var verdict = riskGate.evaluate(relPath, fixSuggestion);
                switch (verdict) {
                    case DENY -> {
                        String denyMsg = "SelfFix: DENIED for " + sourceFile;
                        LOG.warning(denyMsg);
                        return ActionResult.fail(name(), denyMsg, start);
                    }
                    case PR_REQUIRED -> {
                        LOG.info("SelfFix: PR_REQUIRED for " + sourceFile + " — creating branch");
                        prRequired = true;
                    }
                    case ALLOW -> {} // proceed normally
                }
            }

            // ── Write fix ──────────────────────────────────────────
            Path fixDir = Path.of(projectDir, ".self-fixes");
            Files.createDirectories(fixDir);
            Files.writeString(fixDir.resolve(className + ".fix.txt"), fixSuggestion);

            String summary;
            if (prRequired && branchManager != null) {
                // Create feature branch + PR instead of compiling
                String branch = branchManager.createBranch(relPath, fixSuggestion, desc);
                if (branch != null) {
                    summary = "SelfFix: PR created — branch=" + branch + " file=" + sourceFile;
                    return ActionResult.ok(name(), summary, start);
                }
            }

            // ── Compile check (module-level, non-kernel) ──────────
            // ── Compile Repair Loop (Phase 12d) ──────────────────
            if (compileRepair != null) {
                String targetClassName = className.contains(".")
                        ? className.substring(className.lastIndexOf(".") + 1)
                        : className;
                var result = compileRepair.repair(fixSuggestion, targetClassName);
                boolean ok = result.success();
                summary = String.format("SelfFix: %s -> %s (file=%s, attempts=%d)",
                        className, ok ? "COMPILE_OK" : "COMPILE_FAILED", sourceFile, result.attempts());
                LOG.info(summary);
                if (ok) {
                    // Save the repaired version
                    Files.writeString(fixDir.resolve(className + ".repaired.java"), result.compiledCode());
                    return ActionResult.ok(name(), summary, start);
                } else {
                    String diag = result.diagnostics();
                    if (diag.length() > 200) diag = diag.substring(0, 200);
                    return ActionResult.fail(name(), "Compile failed after " + result.attempts()
                            + " attempts: " + diag, start);
                }
            } else {
                // Fallback: old Maven compile
                boolean compiled = runMvnCompile();
                summary = String.format("SelfFix: %s -> %s (file=%s)",
                        className, compiled ? "COMPILE_OK" : "COMPILE_FAILED", sourceFile);
                LOG.info(summary);
                return compiled
                        ? ActionResult.ok(name(), summary, start)
                        : ActionResult.fail(name(), "SelfFix: compile failed: " + summary, start);
            }

        } catch (Exception e) {
            LOG.warning("SelfFixAction failed: " + e.getMessage());
            return ActionResult.fail(name(), "SelfFix error: " + e.getMessage(), start);
        }
    }

    private String findSourceFile(String className) {
        var paths = java.util.List.of(
                Path.of(projectDir, "agicore-modules", "src", "main", "java"),
                Path.of(projectDir, "agicore-kernel", "src", "main", "java"));
        for (var base : paths) {
            try (var files = java.nio.file.Files.walk(base)) {
                var match = files.filter(f -> f.toString().endsWith(className + ".java"))
                        .findFirst();
                if (match.isPresent()) {
                    return match.get().toString();
                }
            } catch (IOException ignored) {}
        }
        return null;
    }

    /** Converts full path like ".../agicore-kernel/src/main/java/de/metis/kernel/core/AgentCoreLoop.java"
     *  to relative "kernel/core/AgentCoreLoop.java" for RiskGate matching. */
    private String deriveRelativePath(String fullPath) {
        if (fullPath == null) return "";
        String reduced = fullPath;
        // Remove everything up to "de/metis/"
        int idx = reduced.indexOf("de/metis/");
        if (idx >= 0) {
            reduced = reduced.substring(idx + 9); // "de/metis/" length=9
        }
        // Remove "kernel/" or "modules/" prefix for the check
        return reduced;
    }

    @SuppressWarnings("unchecked")
    private String generateFix(String className, String sourceFile, String error) throws Exception {
        String sourceContent = "(file not readable)";
        try {
            sourceContent = Files.readString(Path.of(sourceFile));
        } catch (IOException ignored) {}

        String prompt = "Fix this Java compilation error in " + className + ".\n"
                + "ERROR: " + error + "\n\n"
                + "SOURCE:\n" + sourceContent + "\n\n"
                + "Return ONLY the corrected Java code, no explanation.";

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
                .uri(URI.create(ollamaUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(requestNode)))
                .timeout(Duration.ofSeconds(60)).build();

        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;
        String text = MAPPER.readTree(resp.body()).path("response").asText("").trim();

        // Extract code block if present
        int cb = text.indexOf("```java");
        if (cb >= 0) {
            int ce = text.indexOf("```", cb + 7);
            if (ce > cb) return text.substring(cb + 7, ce).trim();
        }
        int cb2 = text.indexOf("```");
        if (cb2 >= 0) {
            int ce2 = text.indexOf("```", cb2 + 3);
            if (ce2 > cb2) return text.substring(cb2 + 3, ce2).trim();
        }
        return text;
    }

    private boolean runMvnCompile() {
        try {
            ProcessBuilder pb = new ProcessBuilder("mvn", "compile", "-q",
                    "-pl", "agicore-modules", "-am");
            pb.directory(Path.of(projectDir).toFile());
            pb.redirectErrorStream(true);
            return pb.start().waitFor() == 0;
        } catch (Exception e) {
            LOG.warning("Compile failed: " + e.getMessage());
            return false;
        }
    }
}
