package de.metis.kernel.optimize;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * A/B Testing service for prompt variants in production.
 * <p>
 * Splits traffic between two prompt variants, collects outcome metrics
 * (success rate, latency, confidence, judge score), and determines
 * the winning variant via statistical comparison.
 * <p>
 * Implements Huyen (2025) Kap.3 principle: "Rigorous evaluation through
 * controlled experiments in production."
 *
 * <h3>Usage</h3>
 * <pre>
 * ABTestService ab = new ABTestService();
 * ab.registerVariant("baseline", "You are Metis...", "standard prompt");
 * ab.registerVariant("cot-v2", "You are Metis...\nThink deeply...", "enhanced CoT");
 *
 * // In planner:
 * ABTestService.Variant variant = ab.selectVariant();
 * String prompt = variant.promptSystem() + "\n" + variant.promptCatalog() + ...;
 * // ... execute planning ...
 * ab.recordOutcome(variant, success, latencyMs, confidence, judgeScore);
 *
 * // Check for winner:
 * if (ab.hasWinner()) {
 *     ABTestService.Variant winner = ab.winner();
 *     ab.promoteWinner();
 * }
 * </pre>
 *
 * <h3>Persistence</h3>
 * Metrics are saved to {@code ab-test-metrics.json} for survival across restarts.
 */
public class ABTestService {

    private static final Logger LOG = Logger.getLogger(ABTestService.class.getName());
    private static final Path METRICS_FILE = Path.of("ab-test-metrics.json");

    /** Minimum samples per variant before winner can be declared. */
    private final int minSamplesPerVariant;

    /** p-value threshold for statistical significance (default 0.05). */
    private final double significanceThreshold;

    /** Traffic split ratio (0.0 = all B, 1.0 = all A). */
    private double trafficSplit;

    private final List<Variant> variants = new ArrayList<>();
    private final Map<String, VariantMetrics> metrics = new LinkedHashMap<>();
    private String activeWinner = null;
    private boolean experimentRunning = false;
    private Instant experimentStarted;

    // ── Construction ──────────────────────────────────────────

    public ABTestService() {
        this(20, 0.05, 0.5);
    }

    public ABTestService(int minSamplesPerVariant, double significanceThreshold, double trafficSplit) {
        this.minSamplesPerVariant = minSamplesPerVariant;
        this.significanceThreshold = significanceThreshold;
        this.trafficSplit = trafficSplit;
        loadMetrics();
    }

    // ── Variant Management ────────────────────────────────────

    /**
     * Register a prompt variant for testing.
     *
     * @param name         unique identifier (e.g. "baseline", "cot-v2")
     * @param promptSystem the full system prompt text for this variant
     * @param promptCatalog the action catalog text
     * @param description  human-readable description of what's different
     */
    public void registerVariant(String name, String promptSystem, String promptCatalog, String description) {
        variants.add(new Variant(name, promptSystem, promptCatalog, description));
        metrics.putIfAbsent(name, new VariantMetrics());
        LOG.info("ABTest: registered variant '" + name + "' — " + description);
    }

    /**
     * Start a new experiment. Resets all metrics.
     */
    public void startExperiment() {
        metrics.clear();
        for (Variant v : variants) {
            metrics.put(v.name(), new VariantMetrics());
        }
        activeWinner = null;
        experimentRunning = true;
        experimentStarted = Instant.now();
        LOG.info("ABTest: experiment started with " + variants.size() + " variants, "
                + "split " + String.format("%.0f/%.0f", trafficSplit * 100, (1 - trafficSplit) * 100));
    }

    /**
     * Select a variant for the current request using weighted random selection.
     */
    public Variant selectVariant() {
        if (variants.isEmpty()) return null;
        if (variants.size() == 1) return variants.get(0);

        // If we have a promoted winner, always use it
        if (activeWinner != null) {
            return variants.stream().filter(v -> v.name().equals(activeWinner)).findFirst().orElse(variants.get(0));
        }

        // Weighted random: first variant gets trafficSplit, second gets the rest
        double roll = Math.random();
        if (roll < trafficSplit || variants.size() < 2) {
            return variants.get(0);
        }
        return variants.get(Math.min(1 + (int)((roll - trafficSplit) / ((1.0 - trafficSplit) / (variants.size() - 1))), variants.size() - 1));
    }

    // ── Outcome Recording ─────────────────────────────────────

    /**
     * Record the outcome of a planning call using the given variant.
     *
     * @param variant      the variant that was used
     * @param success      whether the plan was executed successfully
     * @param latencyMs    planning latency in milliseconds
     * @param confidence   the LLM's self-reported confidence (0-1)
     * @param judgeScore   LLM-as-Judge quality score (0-1, optional, -1 if unavailable)
     */
    public void recordOutcome(Variant variant, boolean success, long latencyMs,
                               double confidence, double judgeScore) {
        if (variant == null) return;
        VariantMetrics m = metrics.get(variant.name());
        if (m == null) {
            m = new VariantMetrics();
            metrics.put(variant.name(), m);
        }
        m.uses++;
        if (success) m.successes++;
        m.totalLatencyMs += latencyMs;
        m.totalConfidence += confidence;
        if (judgeScore >= 0) {
            m.totalJudgeScore += judgeScore;
            m.judgeScoreCount++;
        }
        m.lastUsed = Instant.now();

        // Periodic persistence every 10 samples
        if (totalSamples() % 10 == 0) {
            saveMetrics();
        }
    }

    // ── Winner Determination ──────────────────────────────────

    /**
     * Check if a statistically significant winner has emerged.
     */
    public boolean hasWinner() {
        if (variants.size() < 2) return true;
        if (activeWinner != null) return true;

        // Need minimum samples from each variant
        for (Variant v : variants) {
            VariantMetrics m = metrics.get(v.name());
            if (m == null || m.uses < minSamplesPerVariant) return false;
        }

        // Compare variant 0 vs variant 1 (primary metric: success rate)
        VariantMetrics m0 = metrics.get(variants.get(0).name());
        VariantMetrics m1 = metrics.get(variants.get(1).name());
        if (m0 == null || m1 == null) return false;

        double rate0 = m0.successRate();
        double rate1 = m1.successRate();

        // Fisher's exact test approximation: chi-squared
        double pooledRate = (double)(m0.successes + m1.successes) / (m0.uses + m1.uses);
        double se = Math.sqrt(pooledRate * (1 - pooledRate) * (1.0 / m0.uses + 1.0 / m1.uses));

        if (se == 0) return false; // no variance, need more data

        double z = (rate0 - rate1) / se;
        double pValue = 2 * (1 - cumulativeNormal(Math.abs(z)));

        boolean significant = pValue < significanceThreshold;
        if (significant) {
            double winningRate = Math.max(rate0, rate1);
            String winner = rate0 > rate1 ? variants.get(0).name() : variants.get(1).name();
            LOG.info("ABTest: winner detected! " + winner
                    + " (success rate " + String.format("%.1f%%", winningRate * 100)
                    + " vs " + String.format("%.1f%%", Math.min(rate0, rate1) * 100)
                    + ", p=" + String.format("%.4f", pValue) + ")");
        }
        return significant;
    }

    /**
     * Get the winning variant, or null if no winner yet.
     */
    public Variant winner() {
        if (!hasWinner()) return null;
        if (activeWinner != null) {
            return variants.stream().filter(v -> v.name().equals(activeWinner)).findFirst().orElse(null);
        }
        VariantMetrics m0 = metrics.get(variants.get(0).name());
        VariantMetrics m1 = metrics.get(variants.get(1).name());
        if (m0 == null || m1 == null) return null;
        return m0.successRate() > m1.successRate() ? variants.get(0) : variants.get(1);
    }

    /**
     * Promote the current winner as the permanent active variant.
     * Stops the experiment and always routes to the winner.
     */
    public Variant promoteWinner() {
        Variant winner = winner();
        if (winner == null) return null;
        activeWinner = winner.name();
        experimentRunning = false;
        saveMetrics();
        LOG.info("ABTest: promoted '" + winner.name() + "' as permanent variant. "
                + "Experiment concluded after " + totalSamples() + " samples.");
        return winner;
    }

    // ── Statistics ────────────────────────────────────────────

    /** Total samples across all variants. */
    public int totalSamples() {
        return metrics.values().stream().mapToInt(m -> m.uses).sum();
    }

    /** Get metrics for a variant by name. */
    public VariantMetrics getMetrics(String variantName) {
        return metrics.get(variantName);
    }

    /** Summary report as formatted string. */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== A/B TEST REPORT ===\n");
        sb.append("Experiment: ").append(experimentRunning ? "RUNNING" : "CONCLUDED").append("\n");
        if (experimentStarted != null) {
            sb.append("Started: ").append(experimentStarted).append("\n");
        }
        sb.append("Total samples: ").append(totalSamples()).append("\n");
        sb.append("Min samples/variant: ").append(minSamplesPerVariant).append("\n");
        sb.append("Traffic split: ").append(String.format("%.0f/%.0f", trafficSplit * 100, (1 - trafficSplit) * 100)).append("\n\n");

        for (Variant v : variants) {
            VariantMetrics m = metrics.get(v.name());
            if (m == null) continue;
            boolean isWinner = v.name().equals(activeWinner);
            sb.append(isWinner ? "★ " : "  ");
            sb.append(v.name()).append(" (").append(v.description()).append(")\n");
            sb.append("    Uses: ").append(m.uses).append("\n");
            sb.append("    Success rate: ").append(String.format("%.1f%%", m.successRate() * 100))
                    .append(" (").append(m.successes).append("/").append(m.uses).append(")\n");
            if (m.uses > 0) {
                sb.append("    Avg latency: ").append(String.format("%.0fms", m.avgLatencyMs())).append("\n");
                sb.append("    Avg confidence: ").append(String.format("%.3f", m.avgConfidence())).append("\n");
                if (m.judgeScoreCount > 0) {
                    sb.append("    Avg judge score: ").append(String.format("%.3f", m.avgJudgeScore())).append("\n");
                }
            }
            sb.append("\n");
        }

        if (hasWinner() && activeWinner == null) {
            Variant w = winner();
            sb.append("→ STATISTICALLY SIGNIFICANT WINNER: ").append(w != null ? w.name() : "none")
                    .append(" — ready to promote!\n");
        } else if (activeWinner != null) {
            sb.append("→ PROMOTED: ").append(activeWinner).append(" is now the permanent variant.\n");
        } else {
            int minSamples = metrics.values().stream().mapToInt(m -> m.uses).min().orElse(0);
            sb.append("→ Need ").append(minSamplesPerVariant - minSamples)
                    .append(" more samples from lagging variant(s) for significance.\n");
        }

        return sb.toString();
    }

    // ── Persistence ───────────────────────────────────────────

    private void saveMetrics() {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"activeWinner\": ").append(activeWinner != null ? "\"" + activeWinner + "\"" : "null").append(",\n");
            json.append("  \"experimentRunning\": ").append(experimentRunning).append(",\n");
            json.append("  \"experimentStarted\": \"").append(experimentStarted != null ? experimentStarted.toString() : "").append("\",\n");
            json.append("  \"trafficSplit\": ").append(trafficSplit).append(",\n");
            json.append("  \"variants\": [\n");
            for (int i = 0; i < variants.size(); i++) {
                Variant v = variants.get(i);
                VariantMetrics m = metrics.get(v.name());
                json.append("    {\n");
                json.append("      \"name\": \"").append(escapeJson(v.name())).append("\",\n");
                json.append("      \"description\": \"").append(escapeJson(v.description())).append("\",\n");
                json.append("      \"uses\": ").append(m != null ? m.uses : 0).append(",\n");
                json.append("      \"successes\": ").append(m != null ? m.successes : 0).append(",\n");
                json.append("      \"totalLatencyMs\": ").append(m != null ? m.totalLatencyMs : 0).append(",\n");
                json.append("      \"totalConfidence\": ").append(m != null ? m.totalConfidence : 0).append(",\n");
                json.append("      \"totalJudgeScore\": ").append(m != null ? m.totalJudgeScore : 0).append(",\n");
                json.append("      \"judgeScoreCount\": ").append(m != null ? m.judgeScoreCount : 0).append("\n");
                json.append("    }").append(i < variants.size() - 1 ? "," : "").append("\n");
            }
            json.append("  ]\n");
            json.append("}\n");
            Files.writeString(METRICS_FILE, json.toString());
        } catch (IOException e) {
            LOG.warning("ABTest: failed to save metrics: " + e.getMessage());
        }
    }

    private void loadMetrics() {
        if (!Files.exists(METRICS_FILE)) return;
        try {
            String content = Files.readString(METRICS_FILE);
            // Basic JSON parsing without Jackson
            activeWinner = parseJsonString(content, "activeWinner");
            if ("null".equals(activeWinner)) activeWinner = null;
            experimentRunning = parseJsonBoolean(content, "experimentRunning");
            String started = parseJsonString(content, "experimentStarted");
            if (started != null && !started.isEmpty() && !"null".equals(started)) {
                experimentStarted = Instant.parse(started);
            }
            String splitStr = extractJsonNumber(content, "trafficSplit");
            if (splitStr != null) trafficSplit = Double.parseDouble(splitStr);

            // Parse variants array
            int arrStart = content.indexOf("\"variants\"");
            if (arrStart >= 0) {
                int objStart = content.indexOf("{", arrStart);
                int objEnd = content.lastIndexOf("}");
                if (objStart >= 0 && objEnd > objStart) {
                    String[] variantBlocks = content.substring(objStart + 1, objEnd).split("\\},\\s*\\{");
                    for (String block : variantBlocks) {
                        block = block.trim();
                        if (block.isEmpty()) continue;
                        if (!block.startsWith("{")) block = "{" + block;
                        if (!block.endsWith("}")) block = block + "}";

                        String name = parseJsonString(block, "name");
                        String desc = parseJsonString(block, "description");
                        int uses = parseIntSafe(extractJsonNumber(block, "uses"));
                        int successes = parseIntSafe(extractJsonNumber(block, "successes"));
                        long latency = parseLongSafe(extractJsonNumber(block, "totalLatencyMs"));
                        double confidence = parseDoubleSafe(extractJsonNumber(block, "totalConfidence"));
                        double judgeScore = parseDoubleSafe(extractJsonNumber(block, "totalJudgeScore"));
                        int judgeCount = parseIntSafe(extractJsonNumber(block, "judgeScoreCount"));

                        VariantMetrics m = new VariantMetrics();
                        m.uses = uses;
                        m.successes = successes;
                        m.totalLatencyMs = latency;
                        m.totalConfidence = confidence;
                        m.totalJudgeScore = judgeScore;
                        m.judgeScoreCount = judgeCount;
                        metrics.put(name, m);
                    }
                }
            }
            LOG.info("ABTest: loaded metrics for " + metrics.size() + " variants from " + METRICS_FILE);
        } catch (Exception e) {
            LOG.warning("ABTest: failed to load metrics: " + e.getMessage());
        }
    }

    // ── JSON helpers ──────────────────────────────────────────

    private static String parseJsonString(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        idx += search.length();
        while (idx < json.length() && Character.isWhitespace(json.charAt(idx))) idx++;
        if (idx >= json.length()) return null;
        if (json.charAt(idx) == '"') {
            int start = idx + 1;
            int end = json.indexOf('"', start);
            if (end < 0) return null;
            return json.substring(start, end);
        }
        if (json.startsWith("null", idx)) return "null";
        // number or boolean
        int end = idx;
        while (end < json.length() && !Character.isWhitespace(json.charAt(end)) && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(idx, end);
    }

    private static boolean parseJsonBoolean(String json, String key) {
        String val = parseJsonString(json, key);
        return "true".equals(val);
    }

    private static String extractJsonNumber(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        idx += search.length();
        while (idx < json.length() && Character.isWhitespace(json.charAt(idx))) idx++;
        if (idx >= json.length()) return null;
        int end = idx;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-' || json.charAt(end) == 'e' || json.charAt(end) == 'E')) end++;
        if (end == idx) return null;
        return json.substring(idx, end);
    }

    private static int parseIntSafe(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static long parseLongSafe(String s) {
        if (s == null) return 0;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    private static double parseDoubleSafe(String s) {
        if (s == null) return 0;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /**
     * Cumulative standard normal distribution (Abramowitz & Stegun approximation).
     */
    private static double cumulativeNormal(double x) {
        double b0 = 0.2316419;
        double b1 = 0.319381530;
        double b2 = -0.356563782;
        double b3 = 1.781477937;
        double b4 = -1.821255978;
        double b5 = 1.330274429;
        double t = 1.0 / (1.0 + b0 * x);
        double pdf = Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI);
        double cdf = 1.0 - pdf * (b1 * t + b2 * t * t + b3 * t * t * t + b4 * t * t * t * t + b5 * t * t * t * t * t);
        return x >= 0 ? cdf : 1.0 - cdf;
    }

    // ── Inner Types ───────────────────────────────────────────

    /**
     * A named prompt variant for A/B testing.
     */
    public record Variant(String name, String promptSystem, String promptCatalog, String description) {
        @Override
        public String toString() {
            return name + " (" + description + ")";
        }
    }

    /**
     * Accumulated metrics for a single variant.
     */
    public static class VariantMetrics {
        int uses = 0;
        int successes = 0;
        long totalLatencyMs = 0;
        double totalConfidence = 0;
        double totalJudgeScore = 0;
        int judgeScoreCount = 0;
        Instant lastUsed;

        public double successRate() {
            return uses > 0 ? (double) successes / uses : 0.0;
        }

        public double avgLatencyMs() {
            return uses > 0 ? (double) totalLatencyMs / uses : 0;
        }

        public double avgConfidence() {
            return uses > 0 ? totalConfidence / uses : 0;
        }

        public double avgJudgeScore() {
            return judgeScoreCount > 0 ? totalJudgeScore / judgeScoreCount : -1;
        }

        public int uses() { return uses; }
        public int successes() { return successes; }
    }
}
