package de.metis.modules.eval;

import de.metis.kernel.eval.*;

/**
 * Scorer for PERFORMANCE tasks.
 * <p>
 * Measures runtime metrics: latency, token throughput, VRAM usage.
 * Gate is HARD — budget violations block promotion.
 */
class PerformanceScorer implements Scorer {

    /** VRAM budget in GB (matches RX 7900 XTX 24 GB). */
    static final double VRAM_BUDGET_GB = 24.0;

    /** P95 latency budget in ms. */
    static final double LATENCY_BUDGET_MS = 10_000;

    @Override
    public MetricResult score(EvalTask task, MetisOutput output) {
        String metric = task.scoring().metric();

        return switch (metric) {
            case "p95_plan_latency_ms" -> {
                double value = output.latencyMs();
                yield new MetricResult(metric, value, task.scoring().gate());
            }
            case "tokens_per_sec" -> {
                double tps = output.latencyMs() > 0
                        ? (double) output.responseTokens() / (output.latencyMs() / 1000.0)
                        : 0;
                yield new MetricResult(metric, tps, task.scoring().gate());
            }
            case "peak_vram_gb" -> {
                // Reported by the invoker via MetisOutput or measured externally
                double vram = extractVram(output);
                yield new MetricResult(metric, vram, task.scoring().gate());
            }
            default -> new MetricResult(metric, output.isError() ? 0 : 1.0, task.scoring().gate());
        };
    }

    /**
     * Extract VRAM usage from output or use a default.
     * Real implementation would query nvidia-smi / rocm-smi.
     */
    private double extractVram(MetisOutput output) {
        // In MVP, report 0 (not measured). Full implementation queries GPU.
        // The harness needs actual GPU metrics from the invoker.
        String text = output.rawText();
        if (text != null && text.contains("vram_gb:")) {
            try {
                int idx = text.indexOf("vram_gb:");
                String num = text.substring(idx + 8).trim().split("[^0-9.]")[0];
                return Double.parseDouble(num);
            } catch (Exception ignored) {}
        }
        return 0.0; // unmeasured in MVP
    }
}
