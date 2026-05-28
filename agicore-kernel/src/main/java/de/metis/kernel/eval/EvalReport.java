package de.metis.kernel.eval;

import java.time.Instant;
import java.util.*;

/**
 * Complete evaluation report from a single harness run.
 * <p>
 * Contains per-category results, gate pass/fail, regression analysis,
 * and model digest pinning.
 * <p>
 * Design: claude_antwort_3.txt, 2026-05-28.
 */
public record EvalReport(
        String benchmarkVersion,
        String metisCommit,
        Map<String, String> modelDigests,
        Instant runAt,
        Map<Category, CategoryResult> results,
        GateResult gate,
        List<Regression> regressions,
        String tier // "SMOKE", "FULL", "EXTENDED"
) {
    /**
     * Aggregated result for a single category across all tasks.
     */
    public record CategoryResult(
            Category category,
            Map<String, MetricStats> metrics
    ) {
        /**
         * Per-metric stats across N runs.
         */
        public record MetricStats(
                String metric,
                Gate gate,
                double mean,
                double stddev,
                int runs
        ) {
            public String summary() {
                return String.format("%s=%.3f±%.3f (n=%d, %s)", metric, mean, stddev, runs, gate);
            }
        }
    }

    /**
     * Overall gate decision.
     */
    public record GateResult(
            boolean ok,
            String reason,
            List<String> failingMetrics
    ) {
        public static GateResult passed() {
            return new GateResult(true, "All metrics within thresholds", List.of());
        }

        public static GateResult failed(String reason, List<String> failingMetrics) {
            return new GateResult(false, reason, List.copyOf(failingMetrics));
        }
    }

    /**
     * Detected regression vs baseline.
     */
    public record Regression(
            String metric,
            double baseline,
            double candidate,
            double delta,
            boolean withinNoise
    ) {
        public String summary() {
            return String.format("%s: %.3f→%.3f (Δ=%.3f, noise=%s)",
                    metric, baseline, candidate, delta, withinNoise);
        }
    }

    /**
     * Human-readable summary for logs/alerts.
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("EvalReport[%s] gate=%s commit=%s\n", tier, gate.ok ? "PASS" : "FAIL", metisCommit));
        for (var entry : results.entrySet()) {
            sb.append("  ").append(entry.getKey()).append(":\n");
            for (var ms : entry.getValue().metrics().values()) {
                sb.append("    ").append(ms.summary()).append("\n");
            }
        }
        if (!regressions.isEmpty()) {
            sb.append("  Regressions:\n");
            for (var r : regressions) {
                sb.append("    ").append(r.summary()).append("\n");
            }
        }
        return sb.toString();
    }

    public String gateJson() {
        return String.format("{\"pass\":%b,\"reason\":\"%s\",\"failingMetrics\":%s}",
                gate.ok, gate.reason, failingMetricsJson());
    }

    private String failingMetricsJson() {
        if (gate.failingMetrics.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < gate.failingMetrics.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(gate.failingMetrics.get(i)).append("\"");
        }
        return sb.append("]").toString();
    }
}
