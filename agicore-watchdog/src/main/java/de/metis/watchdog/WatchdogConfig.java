package de.metis.watchdog;

/**
 * Watchdog configuration — the only non-mutable part of the Metis system.
 * <p>
 * Loaded from a read-only directory outside Metis' edit surface.
 * The Watchdog can only HALT, ROLLBACK, or ALERT — no intelligence.
 * <p>
 * Design: claude_antwort_2.txt, 2026-05-28.
 */
public record WatchdogConfig(
        /** Metis HTTP health endpoint */
        String metisHealthUrl,

        /** Heartbeat interval in seconds */
        int heartbeatIntervalSec,

        /** Max missed heartbeats before HALT */
        int maxMissedHeartbeats,

        /** Max consecutive failures before ROLLBACK */
        int maxConsecutiveFailures,

        /** Max error rate before ROLLBACK (0.0-1.0) */
        double maxErrorRate,

        /** Telegram chat ID for ALERT notifications */
        String alertChatId,

        /** Git working directory for ROLLBACK */
        String gitWorkDir,

        /** Known-good commit to rollback to */
        String knownGoodCommit,

        /** Allowed write directories for Metis (anything else → HALT) */
        java.util.Set<String> allowedWriteDirs,

        /** Max CPU usage (0.0-1.0) before resource runaway HALT */
        double maxCpuUsage,

        /** Max duration in seconds for sustained >90% CPU before HALT */
        int resourceRunawaySec,

        /** Audit log path (append-only, hash-chained) */
        String auditLogPath,

        /** Directory where EvalHarness writes reports (Watchdog reads them) */
        String evalReportDir,

        /** Metis API endpoint for model pruning */
        String metisPruneUrl
) {
    public WatchdogConfig {
        if (metisHealthUrl == null || metisHealthUrl.isBlank())
            throw new IllegalArgumentException("metisHealthUrl required");
        if (heartbeatIntervalSec < 1) heartbeatIntervalSec = 5;
        if (maxMissedHeartbeats < 1) maxMissedHeartbeats = 6; // 30s at 5s interval
    }

    /** Default config for the miniedi deployment. */
    public static WatchdogConfig defaultConfig() {
        return new WatchdogConfig(
                "http://192.168.22.204:11735/api/status",
                5,      // heartbeat every 5s
                6,      // halt after 30s no heartbeat
                20,     // rollback after 20 consecutive failures
                0.3,    // rollback at 30% error rate
                "265324594", // Georg's Telegram
                "/home/prometheus/metis-agent-repo",
                "",     // known-good commit set at startup
                java.util.Set.of(
                        "/home/prometheus/metis-agent-repo/",
                        "/tmp/metis-"
                ),
                0.9,    // 90% CPU max
                60,     // 60s sustained runaway → HALT
                "/home/prometheus/metis-agent-repo/metis-audit.log",
                "/home/prometheus/metis/eval-reports",
                "http://192.168.22.204:11735/api/admin/prune"
        );
    }
}
