package de.metis.modules;

import java.util.Locale;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;

/**
 * Blue/Green deployment manager for Metis JAR rollbacks.
 * <p>
 * Maintains two deployment slots:
 * <ul>
 *   <li><b>Blue</b> — current active JAR (running)</li>
 *   <li><b>Green</b> — previous known-good JAR (rollback target)</li>
 * </ul>
 * <p>
 * On deploy: promote blue→green, install new→blue.
 * On rollback: swap green→blue (restore previous version).
 * <p>
 * Health tracking: monitors consecutive failures, error rates, and crash counts.
 * Auto-rollback when thresholds exceeded.
 */
public class RollbackManager {

    private static final Logger LOG = Logger.getLogger(RollbackManager.class.getName());

    private final Path deployDir;
    private final Path blueJar;
    private final Path greenJar;
    private final Path stateFile;

    // Health thresholds (auto-rollback triggers)
    private int maxConsecutiveFailures = 10;
    private double maxErrorRate = 0.3;           // 30% error rate → rollback
    private int minObservationWindow = 20;        // ticks before health assessment
    private int maxCrashCount = 3;               // crashes within window

    // Runtime health
    private int consecutiveFailures = 0;
    private int totalActions = 0;
    private int failedActions = 0;
    private int crashCount = 0;
    private Instant lastRollback = null;
    private Duration rollbackCooldown = Duration.ofMinutes(5);

    // State
    private boolean blueActive = true;
    private boolean autoRollbackEnabled = true;
    private String currentVersion = "unknown";
    private String previousVersion = "none";
    private final List<String> rollbackHistory = new ArrayList<>();

    /**
     * @param deployDir directory containing metis-agent.jar
     */
    public RollbackManager(Path deployDir) {
        this.deployDir = deployDir;
        this.blueJar = deployDir.resolve("metis-agent.jar");
        this.greenJar = deployDir.resolve("metis-agent-green.jar");
        this.stateFile = deployDir.resolve(".rollback-state.json");
        loadState();
    }

    // ── Deployment ────────────────────────────────────────────────

    /**
     * Promote current blue to green, mark new version as blue.
     * Call BEFORE replacing the active JAR.
     */
    public synchronized boolean promoteToBlue(String newVersion) {
        try {
            if (Files.exists(blueJar)) {
                // Backup current blue → green
                Files.copy(blueJar, greenJar, StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Green slot backed up: " + blueJar + " → " + greenJar);
            }

            previousVersion = currentVersion;
            currentVersion = newVersion;
            blueActive = true;

            // Reset health metrics on deploy
            resetHealth();

            saveState();
            LOG.info("Blue/Green promote: " + previousVersion + " → " + currentVersion);
            return true;
        } catch (IOException e) {
            LOG.severe("Blue/Green promote failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Rollback: swap green → blue (restore previous known-good version).
     * @return true if rollback executed, false if no green available
     */
    public synchronized boolean rollback() {
        if (!Files.exists(greenJar)) {
            LOG.warning("Rollback impossible — no green JAR available");
            return false;
        }

        // Cooldown check
        if (lastRollback != null
                && Duration.between(lastRollback, Instant.now()).compareTo(rollbackCooldown) < 0) {
            LOG.warning("Rollback cooldown active — skipping (last: " + lastRollback + ")");
            return false;
        }

        try {
            // Green → blue (restore)
            Files.copy(greenJar, blueJar, StandardCopyOption.REPLACE_EXISTING);
            lastRollback = Instant.now();

            String rolledBackVersion = currentVersion;
            currentVersion = previousVersion;
            previousVersion = rolledBackVersion;

            rollbackHistory.add(Instant.now() + ": " + rolledBackVersion + " → " + currentVersion);
            resetHealth();

            saveState();
            LOG.warning("ROLLBACK executed: " + rolledBackVersion + " → " + currentVersion
                    + ". Restart required to apply.");
            return true;
        } catch (IOException e) {
            LOG.severe("Rollback failed: " + e.getMessage());
            return false;
        }
    }

    // ── Health Monitoring ─────────────────────────────────────────

    /**
     * Record an action outcome for health tracking.
     */
    public synchronized void recordAction(boolean success) {
        totalActions++;
        if (success) {
            consecutiveFailures = 0;
        } else {
            failedActions++;
            consecutiveFailures++;
        }
    }

    /**
     * Record a crash event.
     */
    public synchronized void recordCrash() {
        crashCount++;
        LOG.warning("Crash recorded — count: " + crashCount + "/" + maxCrashCount);
    }

    /**
     * Evaluate health and trigger auto-rollback if thresholds exceeded.
     * @return true if rollback was triggered
     */
    public synchronized boolean evaluateHealth() {
        if (!autoRollbackEnabled) return false;
        if (totalActions < minObservationWindow) return false;

        double errorRate = totalActions > 0 ? (double) failedActions / totalActions : 0;

        boolean shouldRollback = false;
        String reason = "";

        if (consecutiveFailures >= maxConsecutiveFailures) {
            shouldRollback = true;
            reason = consecutiveFailures + " consecutive failures";
        } else if (errorRate > maxErrorRate) {
            shouldRollback = true;
            reason = String.format(Locale.ROOT, "%.0f%% error rate (>%.0f%%)", errorRate * 100, maxErrorRate * 100);
        } else if (crashCount >= maxCrashCount) {
            shouldRollback = true;
            reason = crashCount + " crashes (max " + maxCrashCount + ")";
        }

        if (shouldRollback) {
            LOG.warning("Health check FAILED: " + reason + " — triggering auto-rollback");
            return rollback();
        }

        return false;
    }

    /**
     * Reset health counters (called after deploy/rollback).
     */
    public synchronized void resetHealth() {
        consecutiveFailures = 0;
        totalActions = 0;
        failedActions = 0;
        crashCount = 0;
    }

    // ── Configuration ─────────────────────────────────────────────

    public void setMaxConsecutiveFailures(int n) { this.maxConsecutiveFailures = n; }
    public void setMaxErrorRate(double rate) { this.maxErrorRate = rate; }
    public void setMinObservationWindow(int ticks) { this.minObservationWindow = ticks; }
    public void setMaxCrashCount(int n) { this.maxCrashCount = n; }
    public void setAutoRollbackEnabled(boolean enabled) { this.autoRollbackEnabled = enabled; }

    // ── State ─────────────────────────────────────────────────────

    public String currentVersion() { return currentVersion; }
    public String previousVersion() { return previousVersion; }
    public boolean hasGreenSlot() { return Files.exists(greenJar); }
    public Duration timeSinceLastRollback() {
        return lastRollback == null ? null : Duration.between(lastRollback, Instant.now());
    }
    public List<String> rollbackHistory() { return List.copyOf(rollbackHistory); }
    public int consecutiveFailures() { return consecutiveFailures; }
    public double currentErrorRate() {
        return totalActions > 0 ? (double) failedActions / totalActions : 0;
    }
    public boolean isAutoRollbackEnabled() { return autoRollbackEnabled; }

    // ── Health JSON (for /api/status) ────────────────────────────

    public String healthJson() {
        return String.format(Locale.ROOT, """
                "rollback": {
                  "currentVersion": "%s",
                  "previousVersion": "%s",
                  "greenAvailable": %b,
                  "autoRollback": %b,
                  "consecutiveFailures": %d,
                  "errorRate": %.3f,
                  "crashCount": %d,
                  "lastRollback": "%s",
                  "history": %s
                }""",
                currentVersion, previousVersion, hasGreenSlot(),
                autoRollbackEnabled, consecutiveFailures,
                currentErrorRate(), crashCount,
                lastRollback != null ? lastRollback.toString() : "none",
                rollbackHistory);
    }

    // ── Persistence ───────────────────────────────────────────────

    private void saveState() {
        try {
            String json = String.format(Locale.ROOT, """
                    {
                      "currentVersion": "%s",
                      "previousVersion": "%s",
                      "blueActive": %b,
                      "lastRollback": "%s",
                      "rollbackHistory": %s
                    }""",
                    currentVersion, previousVersion, blueActive,
                    lastRollback != null ? lastRollback.toString() : "",
                    rollbackHistory);
            Files.writeString(stateFile, json);
        } catch (IOException e) {
            LOG.fine("Failed to save rollback state: " + e.getMessage());
        }
    }

    private void loadState() {
        try {
            if (Files.exists(stateFile)) {
                String json = Files.readString(stateFile);
                currentVersion = extractJsonString(json, "currentVersion");
                previousVersion = extractJsonString(json, "previousVersion");
                if (currentVersion == null) currentVersion = "unknown";
                if (previousVersion == null) previousVersion = "none";
                LOG.info("Rollback state loaded: " + currentVersion + " (prev: " + previousVersion + ")");
            }
        } catch (IOException e) {
            LOG.fine("No rollback state file — fresh start");
        }
    }

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : null;
    }
}
