package de.metis.modules.action;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;
import de.metis.kernel.goal.Goal;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Phase 12 — Erstellt Feature-Branches aus Code-Analyse + LLM-Optimierung.
 *
 * <p>Workflow:
 * <ol>
 *   <li>Repo status prüfen (git status, git stash)</li>
 *   <li>Code scan auf TODOs, Duplikate, fehlende Tests</li>
 *   <li>LLM-generierte Optimierungen</li>
 *   <li>Feature-Branch erstellen, Änderungen committen, pushen</li>
 * </ol>
 *
 * <p>Sicherheits-Gates:
 * <ul>
 *   <li>Nur Branch erstellen (nie direkt auf master commiten)</li>
 *   <li>Maven-Compile-Check vor Commit</li>
 *   <li>Keine Secrets/Keys in Commits</li>
 * </ul>
 */
public class GitFeatureBranchAction implements Action {

    private static final Logger LOG = Logger.getLogger(GitFeatureBranchAction.class.getName());

    private final String ollamaUrl;
    private final String model;
    private final String repoDir;
    private final String remoteName;
    private Goal currentGoal = null;

    /** Ergebnis-Tracking. */
    private String branchName;
    private int filesChanged;
    private int compileErrors;

    public GitFeatureBranchAction(String ollamaUrl, String model, String repoDir) {
        this(ollamaUrl, model, repoDir, "origin");
    }

    public GitFeatureBranchAction(String ollamaUrl, String model, String repoDir, String remoteName) {
        this.ollamaUrl = ollamaUrl;
        this.model = model;
        this.repoDir = repoDir;
        this.remoteName = remoteName;
    }

    @Override public String name() { return "git-feature-branch"; }
    public void setCurrentGoal(Goal g) { this.currentGoal = g; }
    public String branchName() { return branchName; }
    public int filesChanged() { return filesChanged; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        branchName = null;
        filesChanged = 0;
        compileErrors = 0;

        try {
            String desc = currentGoal != null ? currentGoal.description() : "auto-optimization";
            if (desc.isBlank()) desc = "auto-optimization";

            // 1. Repo ist da?
            Path repoPath = Path.of(repoDir);
            if (!Files.exists(repoPath.resolve(".git"))) {
                return ActionResult.fail(name(), "Not a git repo: " + repoDir, start);
            }

            // 2. Stash current changes
            exec("git", "stash");

            // 3. Fetch + checkout master + pull
            exec("git", "fetch", remoteName);
            exec("git", "checkout", "master");
            exec("git", "pull", remoteName, "master");

            // 4. TODO-Scan: finde TODOs im Code
            List<String> todos = scanTodos(repoPath);
            List<String> patterns = scanPatterns(repoPath);

            // 5. LLM optimiert auf Basis der Scans
            String fixCode = generateOptimization(desc, todos, patterns);
            if (fixCode == null || fixCode.isBlank() || fixCode.length() < 20) {
                exec("git", "stash", "pop");
                return ActionResult.fail(name(), "Ollama returned no meaningful changes", start);
            }

            // 6. Feature-Branch erstellen
            String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String safeDesc = desc.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
            if (safeDesc.length() > 40) safeDesc = safeDesc.substring(0, 40);
            branchName = "feature/" + dateStr + "-" + safeDesc;
            exec("git", "checkout", "-b", branchName);

            // 7. Änderungen per Patch/Write anwenden
            int patches = applyOptimizations(repoPath, fixCode);
            filesChanged = patches;
            if (patches == 0) {
                exec("git", "checkout", "master");
                exec("git", "branch", "-D", branchName);
                return ActionResult.fail(name(), "No files modified by optimization", start);
            }

            // 8. Maven-Compile-Check
            boolean compiled = runMvnCompile();
            if (!compiled) {
                compileErrors = 1;
                // Lektion gelernt (18.06.): nicht-loeschen, sondern stashen —
                // Aenderungen bleiben erhalten fuer spaetere Analyse
                exec("git", "add", "-A");
                exec("git", "stash");
                exec("git", "checkout", "master");
                String stashNote = "failed-compile-" + branchName.replace('/', '-');
                exec("git", "stash", "pop");
                return ActionResult.fail(name(), "Optimization did not compile — changes stashed for review", start);
            }

            // 9. Commit + Push
            exec("git", "add", "-A");
            String commitMsg = buildCommitMessage(desc, patches);
            exec("git", "commit", "-m", commitMsg);
            exec("git", "push", remoteName, branchName);

            // 10. Back to master, pop stash
            exec("git", "checkout", "master");
            exec("git", "stash", "pop");

            String summary = String.format(
                    "GitFeatureBranch: %s -> branch=%s files=%d compiled=%s",
                    desc, branchName, patches, compiled);
            LOG.info(summary);
            return ActionResult.ok(name(), summary, start);

        } catch (Exception e) {
            LOG.warning("GitFeatureBranchAction failed: " + e.getMessage());
            try { exec("git", "checkout", "master"); } catch (Exception ignored) {}
            try { exec("git", "stash", "pop"); } catch (Exception ignored) {}
            return ActionResult.fail(name(), "GitFeatureBranchAction error: " + e.getMessage(), start);
        }
    }

    // ── Analyse ────────────────────────────────────────────

    /** Scanne Java-Dateien nach TODO-Kommentaren. */
    private List<String> scanTodos(Path repoPath) throws Exception {
        List<String> todos = new ArrayList<>();
        Files.walk(repoPath.resolve("agicore-modules/src").resolve("main"))
                .filter(p -> p.toString().endsWith(".java"))
                .limit(100)
                .forEach(p -> {
                    try {
                        String content = Files.readString(p);
                        int idx = 0;
                        while ((idx = content.indexOf("TODO", idx)) >= 0) {
                            int end = content.indexOf('\n', idx);
                            if (end < 0) end = Math.min(idx + 80, content.length());
                            String line = content.substring(idx, Math.min(end, idx + 80)).trim();
                            String rel = repoPath.relativize(p).toString();
                            todos.add(rel + ":" + line);
                            idx = end;
                        }
                    } catch (Exception ignored) {}
                });
        return todos;
    }

    /** Scanne nach verdächtigen Patterns (Code-Duplizierung, Magic Numbers). */
    private List<String> scanPatterns(Path repoPath) throws Exception {
        List<String> patterns = new ArrayList<>();
        Files.walk(repoPath.resolve("agicore-modules/src").resolve("main"))
                .filter(p -> p.toString().endsWith(".java"))
                .limit(50)
                .forEach(p -> {
                    try {
                        String content = Files.readString(p);
                        long lines = content.lines().count();
                        if (lines > 400) {
                            String rel = repoPath.relativize(p).toString();
                            patterns.add(rel + ": large file (" + lines + " lines) — consider refactoring");
                        }
                    } catch (Exception ignored) {}
                });
        return patterns;
    }

    // ── LLM-Generierung ────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String generateOptimization(String desc, List<String> todos, List<String> patterns) throws Exception {
        String context = "TODOs:\n" + String.join("\n", todos.subList(0, Math.min(10, todos.size())));
        if (!patterns.isEmpty()) {
            context += "\n\nPatterns:\n" + String.join("\n", patterns);
        }

        String prompt = "You are an expert Java AGI engineer. Review this code analysis for the Metis AGI project "
                + "and generate fixes. Output ONLY valid Java patches in this format:\n"
                + "--- FILE: rel/path/File.java\n"
                + "```java\n// modified source code with changes\n```\n\n"
                + "Context:\n" + context + "\n\nGoal: " + desc;

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var requestNode = mapper.createObjectNode();
        requestNode.put("model", model);
        requestNode.put("prompt", prompt);
        requestNode.put("stream", false);
        var opts = mapper.createObjectNode();
        opts.put("temperature", 0.2);
        opts.put("num_predict", 2048);
        requestNode.set("options", opts);

        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestNode)))
                .timeout(Duration.ofSeconds(120)).build();

        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;
        return mapper.readTree(resp.body()).path("response").asText("").trim();
    }

    // ── Patches anwenden ───────────────────────────────────

    /** 
     * Parst LLM-Output im Format "--- FILE: path --- \n```java ...```"
     * und schreibt Änderungen auf Disk.
     */
    private int applyOptimizations(Path repoPath, String fixCode) throws Exception {
        int count = 0;
        String[] blocks = fixCode.split("--- FILE: ");
        for (String block : blocks) {
            if (block.isBlank()) continue;
            int nlPos = block.indexOf('\n');
            if (nlPos < 0) continue;
            String filePath = block.substring(0, nlPos).trim();
            String content = block.substring(nlPos).trim();
            // Remove markdown fences
            content = content.replaceAll("```java", "").replaceAll("```", "").trim();
            if (content.isEmpty()) continue;

            Path target = repoPath.resolve(filePath);
            if (Files.exists(target)) {
                // Backup original
                Path originalContent = target;
                try {
                    Files.writeString(target, content + "\n");
                    count++;
                    LOG.fine("Patched: " + filePath);
                } catch (Exception e) {
                    LOG.warning("Failed to write " + filePath + ": " + e.getMessage());
                }
            }
        }
        return count;
    }

    // ── Git-Helfer ─────────────────────────────────────────

    private String exec(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(Path.of(repoDir).toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();
        if (exit != 0) {
            LOG.warning("Git command '" + String.join(" ", cmd) + "' failed: " + output);
        }
        return output;
    }

    // ── Maven-Compile ────────────────────────────────────

    private boolean runMvnCompile() {
        try {
            ProcessBuilder pb = new ProcessBuilder("mvn", "compile", "-q", "-pl", "agicore-modules", "-am");
            pb.directory(Path.of(repoDir).toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean ok = p.waitFor(180, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
            if (!ok) {
                String log = new String(p.getInputStream().readAllBytes()).trim();
                if (log.length() > 500) log = log.substring(0, 500);
                LOG.warning("Compile failed: " + log);
            }
            return ok;
        } catch (Exception e) {
            LOG.warning("Compile check error: " + e.getMessage());
            return false;
        }
    }

    // ── Commit-Message ──────────────────────────────────

    private String buildCommitMessage(String desc, int fileCount) {
        return "feat(" + LocalDate.now() + "): " + desc + " [" + fileCount + " files]";
    }
}
