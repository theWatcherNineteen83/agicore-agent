package de.metis.watchdog;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * Metis Watchdog — external safety process.
 * <p>
 * Runs in a SEPARATE JVM with code in a READ-ONLY directory outside
 * Metis' edit surface. This is the only non-mutable component.
 * <p>
 * The Watchdog observes Metis and enforces three actions:
 * <ul>
 *   <li><b>HALT</b> — kill Metis process (hard tripwire)</li>
 *   <li><b>ROLLBACK</b> — git reset to known-good commit (soft tripwire)</li>
 *   <li><b>ALERT</b> — notify admin via Telegram (info)</li>
 * </ul>
 * <p>
 * Communication is ONE-WAY: Watchdog observes Metis, Metis has NO handle on Watchdog.
 * <p>
 * Design: claude_antwort_2.txt, 2026-05-28.
 */
public class WatchdogMain {

    private static final Logger LOG = Logger.getLogger(WatchdogMain.class.getName());

    private final WatchdogConfig config;
    private final HttpClient http;
    private final ScheduledExecutorService scheduler;
    private final List<String> actionLog;
    private final AuditLog auditLog;

    private int missedHeartbeats = 0;
    private Instant lastHeartbeat = Instant.now();
    private String currentCommit = "";
    private boolean halted = false;
    private int rollbackCount = 0;
    private Path lastEvalReport = null;  // tracks last processed eval report

    public WatchdogMain(WatchdogConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "watchdog");
            t.setDaemon(true);
            return t;
        });
        this.actionLog = new ArrayList<>();
        this.auditLog = new AuditLog(Path.of(config.auditLogPath()));
    }

    /**
     * Start the watchdog loop. Blocks until halted.
     */
    public void start() {
        LOG.info("Watchdog started — monitoring " + config.metisHealthUrl());
        LOG.info("Config: heartbeat=" + config.heartbeatIntervalSec() + "s, "
                + "maxMissed=" + config.maxMissedHeartbeats()
                + ", maxFailures=" + config.maxConsecutiveFailures());

        // Start prune endpoint (port 11736) for eval-driven model pruning
        var pruneEndpoint = new PruneEndpoint(11736);
        pruneEndpoint.start();

        // Determine current commit at startup
        currentCommit = detectCurrentCommit();

        // Verify audit chain integrity
        if (!auditLog.verify()) {
            LOG.severe("⚠️ AUDIT LOG TAMPERED — hash chain broken!");
            executeAlert("AUDIT LOG TAMPER DETECTED");
        } else {
            LOG.info("AuditLog: " + auditLog.entryCount() + " entries, chain head "
                    + auditLog.lastHash().substring(0, 12) + "...");
        }

        // Start heartbeat loop
        scheduler.scheduleAtFixedRate(this::heartbeatCheck, 1,
                config.heartbeatIntervalSec(), TimeUnit.SECONDS);

        // Start resource monitor (every 30s)
        scheduler.scheduleAtFixedRate(this::resourceCheck, 30, 30, TimeUnit.SECONDS);

        // Start eval report watcher (every 60s)
        scheduler.scheduleAtFixedRate(this::evalReportCheck, 10, 60, TimeUnit.SECONDS);

        // Hourly external anchor of the audit-log chain head.
        // Anchors live in config.auditAnchorDir (outside Metis writable scope).
        // If anchors are git-committed/tagged externally, any later truncation
        // of the audit log becomes detectable.
        scheduler.scheduleAtFixedRate(this::writeAuditAnchor, 60, 3600, TimeUnit.SECONDS);

        // Block main thread
        try {
            while (!halted) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        shutdown();
    }

    // ── Heartbeat Check ────────────────────────────────────────────

    private void heartbeatCheck() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(config.metisHealthUrl()))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                missedHeartbeats = 0;
                lastHeartbeat = Instant.now();
                evaluateHealthMetrics(resp.body());
            } else {
                missedHeartbeats++;
                LOG.fine("Heartbeat non-200: " + resp.statusCode() + " (missed: " + missedHeartbeats + ")");
            }

        } catch (Exception e) {
            missedHeartbeats++;
            LOG.fine("Heartbeat failed: " + e.getMessage() + " (missed: " + missedHeartbeats + ")");
        }

        // Hard tripwire: too many missed heartbeats
        if (missedHeartbeats >= config.maxMissedHeartbeats()) {
            auditLog.append("HEARTBEAT_LOST", missedHeartbeats + " missed (max " + config.maxMissedHeartbeats() + ")");
            trigger(WatchdogAction.HALT, TripwireSeverity.HARD,
                    "Heartbeat lost: " + missedHeartbeats + " missed (max " + config.maxMissedHeartbeats() + ")");
        }
    }

    // ── Health Metrics Evaluation ───────────────────────────────────

    private void evaluateHealthMetrics(String healthJson) {
        try {
            int consecutiveFailures = extractInt(healthJson, "consecutiveFailures");
            double errorRate = extractDouble(healthJson, "errorRate");

            if (consecutiveFailures >= config.maxConsecutiveFailures()) {
                trigger(WatchdogAction.ROLLBACK, TripwireSeverity.SOFT,
                        consecutiveFailures + " consecutive failures (max " + config.maxConsecutiveFailures() + ")");
            } else if (errorRate > config.maxErrorRate()) {
                trigger(WatchdogAction.ROLLBACK, TripwireSeverity.SOFT,
                        "Error rate " + String.format("%.1f%%", errorRate * 100)
                                + " > " + String.format("%.0f%%", config.maxErrorRate() * 100));
            }
        } catch (Exception e) {
            LOG.fine("Health metric parsing failed: " + e.getMessage());
        }
    }

    // ── Resource Monitor ────────────────────────────────────────────

    private void resourceCheck() {
        try {
            double cpu = getCpuUsage();
            if (cpu > config.maxCpuUsage()) {
                // Check sustained: log but don't HALT on first occurrence
                LOG.warning("CPU runaway: " + String.format("%.0f%%", cpu * 100)
                        + " > " + String.format("%.0f%%", config.maxCpuUsage() * 100));
                // TODO: track sustained duration, HALT after resourceRunawaySec
            }
        } catch (Exception e) {
            LOG.fine("Resource check failed: " + e.getMessage());
        }
    }

    private double getCpuUsage() {
        try {
            Process p = new ProcessBuilder("sh", "-c",
                    "top -bn1 | grep 'Cpu(s)' | awk '{print $2+$4}'")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(5, TimeUnit.SECONDS);
            return Double.parseDouble(output) / 100.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    // ── Actions ─────────────────────────────────────────────────────

    /**
     * Execute a watchdog action.
     */
    public synchronized void trigger(WatchdogAction action, TripwireSeverity severity, String reason) {
        String entry = Instant.now() + " [" + severity + "] " + action + ": " + reason;
        actionLog.add(entry);
        LOG.warning(entry);

        // Append to tamper-evident audit chain
        auditLog.append(action.name(), severity + ": " + reason);

        switch (action) {
            case HALT -> executeHalt(reason);
            case ROLLBACK -> executeRollback(reason);
            case ALERT -> executeAlert(reason);
            case PRUNE -> executePrune(reason);
        }
    }

    private void executeHalt(String reason) {
        LOG.severe("⚡ HALT: Killing Metis process — " + reason);
        halted = true;

        try {
            // Find and kill Metis process
            new ProcessBuilder("pkill", "-f", "metis-agent.jar")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.severe("Failed to kill Metis: " + e.getMessage());
        }

        // Alert admin
        executeAlert("HALT: " + reason);
    }

    private void executeRollback(String reason) {
        if (rollbackCount >= 3) {
            LOG.severe("Too many rollbacks (" + rollbackCount + ") — escalating to HALT");
            executeHalt("Rollback limit exceeded: " + reason);
            return;
        }

        rollbackCount++;
        LOG.warning("⟲ ROLLBACK #" + rollbackCount + ": " + reason);

        try {
            String knownGood = config.knownGoodCommit();
            if (knownGood == null || knownGood.isBlank()) {
                knownGood = detectCurrentCommit() + "~1"; // fallback: previous commit
            }

            // Git reset
            ProcessBuilder pb = new ProcessBuilder("git", "-C", config.gitWorkDir(),
                    "reset", "--hard", knownGood);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor(30, TimeUnit.SECONDS);
            LOG.info("Git reset output: " + output);

            // Restart Metis (systemctl)
            new ProcessBuilder("systemctl", "--user", "restart", "metis")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(10, TimeUnit.SECONDS);

        } catch (Exception e) {
            LOG.severe("Rollback failed: " + e.getMessage());
        }

        // Alert admin
        executeAlert("ROLLBACK #" + rollbackCount + ": " + reason);
    }

    private void executeAlert(String message) {
        LOG.info("🔔 ALERT: " + message);
        // Alert is sent via Telegram — the Gateway cron/scheduler handles delivery.
        // The Watchdog writes to a well-known alert file that OpenClaw monitors.
        try {
            Path alertFile = Path.of(config.gitWorkDir(), ".watchdog-alerts.log");
            String line = Instant.now() + " | " + message + "\n";
            Files.writeString(alertFile, line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warning("Failed to write alert file: " + e.getMessage());
        }
    }

    /**
     * Prune underperforming models via the Metis ModelRegistry API.
     * Called when eval reports show a specific model consistently failing.
     */
    private void executePrune(String reason) {
        LOG.warning("✂️ PRUNE: " + reason);

        try {
            // Extract model name from reason (format: "model:qwen3.6:latest metric:planning=0.45")
            String modelName = extractModelFromReason(reason);
            if (modelName == null || modelName.isBlank()) {
                LOG.warning("PRUNE: could not extract model name from reason: " + reason);
                return;
            }

            // Call Metis API to prune the model
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(config.metisPruneUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"model\":\"" + modelName + "\",\"reason\":\"" + reason.replace("\"", "'") + "\"}"))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            LOG.info("PRUNE API response: " + resp.statusCode() + " — " + resp.body());

            if (resp.statusCode() == 200) {
                executeAlert("PRUNE success: " + modelName + " removed from registry — " + reason);
            } else {
                executeAlert("PRUNE failed (HTTP " + resp.statusCode() + "): " + modelName);
            }
        } catch (Exception e) {
            LOG.warning("PRUNE failed: " + e.getMessage());
            executeAlert("PRUNE error: " + e.getMessage());
        }
    }

    /** Extract model name from prune reason string. */
    private String extractModelFromReason(String reason) {
        // Pattern: "model:NAME" or "planning:NAME score:X"
        int modelIdx = reason.indexOf("model:");
        if (modelIdx >= 0) {
            int start = modelIdx + 6;
            int end = reason.indexOf(' ', start);
            return end > start ? reason.substring(start, end) : reason.substring(start);
        }
        // Try category:model format
        for (String cat : new String[]{"planning", "codegen", "mutation"}) {
            int catIdx = reason.indexOf(cat + ":");
            if (catIdx >= 0) {
                int start = catIdx + cat.length() + 1;
                int end = reason.indexOf(' ', start);
                String candidate = end > start ? reason.substring(start, end) : reason.substring(start);
                if (candidate.contains(":") && !candidate.startsWith("0.")) return candidate;
            }
        }
        return null;
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private String detectCurrentCommit() {
        try {
            Process p = new ProcessBuilder("git", "-C", config.gitWorkDir(),
                    "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(5, TimeUnit.SECONDS);
            return output;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private int extractInt(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return 0;
        start += search.length();
        while (start < json.length() && (Character.isWhitespace(json.charAt(start))
                || json.charAt(start) == ':')) start++;
        StringBuilder num = new StringBuilder();
        while (start < json.length() && (Character.isDigit(json.charAt(start))
                || json.charAt(start) == '.' || json.charAt(start) == '-')) {
            num.append(json.charAt(start++));
        }
        try { return Integer.parseInt(num.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private double extractDouble(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return 0;
        start += search.length();
        while (start < json.length() && (Character.isWhitespace(json.charAt(start))
                || json.charAt(start) == ':')) start++;
        StringBuilder num = new StringBuilder();
        while (start < json.length() && (Character.isDigit(json.charAt(start))
                || json.charAt(start) == '.' || json.charAt(start) == '-')) {
            num.append(json.charAt(start++));
        }
        try { return Double.parseDouble(num.toString()); } catch (NumberFormatException e) { return 0; }
    }

    // ── Eval Report Watcher ────────────────────────────────────────

    /**
     * Poll the eval-reports directory for new reports from EvalHarness.
     * If any report has gate.ok == false → ROLLBACK (HARD tripwire).
     * Alerts on new regressions even if gate passes.
     */
    private void evalReportCheck() {
        if (config.evalReportDir() == null || config.evalReportDir().isBlank()) return;

        Path dir = Path.of(config.evalReportDir());
        if (!Files.isDirectory(dir)) return;

        try (var files = Files.list(dir)) {
            files.filter(f -> f.toString().endsWith("-eval-report.json"))
                    .sorted()
                    .forEach(this::processEvalReport);
        } catch (IOException e) {
            LOG.fine("Eval report scan failed: " + e.getMessage());
        }
    }

    private void processEvalReport(Path file) {
        // Skip already-processed reports
        if (lastEvalReport != null && file.getFileName().toString()
                .compareTo(lastEvalReport.getFileName().toString()) <= 0) {
            return;
        }
        lastEvalReport = file;

        try {
            String content = Files.readString(file);
            Boolean ok = extractJsonBool(content, "ok");
            String reason = extractJsonString(content, "reason");
            String tier = extractJsonString(content, "tier");
            int regressionCount = countJsonArrayElements(content, "regressions");

            LOG.info("Eval report [" + tier + "]: gate=" + (ok != null && ok ? "PASS" : "FAIL")
                    + ", regressions=" + regressionCount
                    + ", file=" + file.getFileName());

            if (ok != null && !ok) {
                trigger(WatchdogAction.ROLLBACK, TripwireSeverity.HARD,
                        "Eval gate FAIL [" + tier + "]: " + reason
                                + " (report: " + file.getFileName() + ")");
                // Also prune the failing model(s)
                String pruneReason = "model:" + reason + " tier:" + tier;
                trigger(WatchdogAction.PRUNE, TripwireSeverity.SOFT,
                        "Eval-driven prune: " + pruneReason
                                + " (report: " + file.getFileName() + ")");
            } else if (regressionCount > 0) {
                // Soft alert for regressions even when gate passes
                executeAlert("Eval regressions detected [" + tier + "]: "
                        + regressionCount + " metrics regressed"
                        + " (report: " + file.getFileName() + ")");
            }
        } catch (IOException e) {
            LOG.warning("Failed to read eval report " + file + ": " + e.getMessage());
        }
    }

    // ── Minimal JSON parsing (no Jackson dep in watchdog) ───────────

    private static Boolean extractJsonBool(String json, String key) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\"" + key + "\"\\s*:\\s*(true|false)");
        var m = p.matcher(json);
        return m.find() ? Boolean.parseBoolean(m.group(1)) : null;
    }

    private static String extractJsonString(String json, String key) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
        var m = p.matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static int countJsonArrayElements(String json, String key) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\"" + key + "\"\\s*:\\s*\\[");
        var m = p.matcher(json);
        if (!m.find()) return 0;
        int start = m.end();
        int depth = 1, count = 0, i = start;
        while (i < json.length() && depth > 0) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == '[') depth++;
            else if (c == ']') depth--;
            else if (c == '"' && depth == 1) {
                // hit a string element inside the array
                count++;
                i++; // skip opening quote
                while (i < json.length() && json.charAt(i) != '"') i++;
            }
            i++;
        }
        return count;
    }

    private void shutdown() {
        LOG.info("Watchdog shutting down. Action log: " + actionLog.size() + " events");
        scheduler.shutdown();
    }

    // ── Entry Point ─────────────────────────────────────────────────

    public static void main(String[] args) {
        WatchdogConfig config = WatchdogConfig.defaultConfig();
        WatchdogMain watchdog = new WatchdogMain(config);
        watchdog.start();
    }

    /** Hourly anchor: append-only chain-head snapshot in an external directory. */
    private void writeAuditAnchor() {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("metis.audit.anchor.dir", "/home/prometheus/metis/audit-anchors"));
            java.nio.file.Path written = auditLog.writeAnchor(dir);
            if (written != null) {
                LOG.fine("AuditLog: anchor written -> " + written);
            }
        } catch (Exception e) {
            LOG.warning("AuditLog anchor failed: " + e.getMessage());
        }
    }
}
