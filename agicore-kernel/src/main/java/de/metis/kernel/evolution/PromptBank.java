package de.metis.kernel.evolution;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Collects successful mutations and feeds them back as few-shot examples.
 * <p>
 * This is the "Prompt-Bank" — the memory of what worked. Each accepted
 * mutation becomes a training example for future mutation requests.
 * <p>
 * Format (evolution-history.jsonl):
 * <pre>
 * {"module":"stub-planner","version":"1.1.0","fitness":0.85,"delta":0.03,"timestamp":"...","original":"...","mutated":"..."}
 * </pre>
 */
public class PromptBank {

    private static final Logger LOG = Logger.getLogger(PromptBank.class.getName());
    private static final Path HISTORY_FILE = Path.of("evolution-history.jsonl");

    /** Maximum few-shot examples to include in a prompt. */
    private static final int MAX_FEW_SHOT = 3;

    /** Maximum history entries to keep in memory. */
    private static final int MAX_HISTORY = 100;

    private final List<MutationRecord> history = new ArrayList<>();
    private int versionCounter = 0;

    public PromptBank() {
        loadHistory();
    }

    /**
     * Record a successful mutation.
     */
    public void recordSuccess(String moduleName, double fitness, double delta,
                               String originalSource, String mutatedSource) {
        versionCounter++;
        MutationRecord record = new MutationRecord(
                moduleName, "1." + versionCounter + ".0", fitness, delta,
                Instant.now().toString(), originalSource, mutatedSource);
        history.add(record);

        if (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }

        appendToFile(record);
        LOG.info("PromptBank: recorded mutation #" + versionCounter
                + " for " + moduleName + " (fitness +" + String.format("%.3f", delta) + ")");
    }

    /**
     * Build a few-shot prompt extension from the most successful mutations
     * for a given module.
     */
    public String buildFewShotPrompt(String moduleName) {
        // Get top N mutations for this module by fitness delta
        List<MutationRecord> relevant = history.stream()
                .filter(r -> r.moduleName().equals(moduleName))
                .sorted(Comparator.comparingDouble(MutationRecord::delta).reversed())
                .limit(MAX_FEW_SHOT)
                .toList();

        if (relevant.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\nPREVIOUS SUCCESSFUL MUTATIONS (learn from these patterns):\n\n");

        for (int i = 0; i < relevant.size(); i++) {
            MutationRecord r = relevant.get(i);
            sb.append("--- MUTATION #").append(i + 1)
                    .append(" (fitness +").append(String.format("%.3f", r.delta()))
                    .append(", version ").append(r.version()).append(") ---\n");
            sb.append("ORIGINAL:\n```java\n").append(truncate(r.originalSource(), 1500)).append("\n```\n");
            sb.append("MUTATED (successful):\n```java\n").append(truncate(r.mutatedSource(), 1500)).append("\n```\n\n");
        }

        sb.append("Apply similar improvements to the current code below.");
        return sb.toString();
    }

    /**
     * Best fitness achieved so far for a module.
     */
    public double bestFitness(String moduleName) {
        return history.stream()
                .filter(r -> r.moduleName().equals(moduleName))
                .mapToDouble(MutationRecord::fitness)
                .max()
                .orElse(0.0);
    }

    /** Number of successful mutations recorded. */
    public int successCount() { return history.size(); }

    /** All recorded mutations (unmodifiable). */
    public List<MutationRecord> all() { return List.copyOf(history); }

    private void appendToFile(MutationRecord record) {
        try {
            String json = String.format(
                    "{\"module\":\"%s\",\"version\":\"%s\",\"fitness\":%.4f,\"delta\":%.4f,\"timestamp\":\"%s\"}\n",
                    record.moduleName(), record.version(), record.fitness(),
                    record.delta(), record.timestamp());
            Files.writeString(HISTORY_FILE, json, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warning("Failed to write evolution history: " + e.getMessage());
        }
    }

    private void loadHistory() {
        if (!Files.exists(HISTORY_FILE)) return;
        try {
            Files.lines(HISTORY_FILE).forEach(line -> {
                try {
                    // Simple JSON parsing (no Jackson dependency needed here)
                    String module = extractJsonValue(line, "module");
                    String version = extractJsonValue(line, "version");
                    double fitness = Double.parseDouble(extractJsonValue(line, "fitness"));
                    double delta = Double.parseDouble(extractJsonValue(line, "delta"));
                    String timestamp = extractJsonValue(line, "timestamp");
                    history.add(new MutationRecord(module, version, fitness, delta, timestamp, "", ""));
                } catch (Exception e) {
                    // skip malformed lines
                }
            });
            LOG.info("PromptBank: loaded " + history.size() + " records from " + HISTORY_FILE);
        } catch (IOException e) {
            LOG.warning("Failed to load evolution history: " + e.getMessage());
        }
    }

    private static String extractJsonValue(String json, String key) {
        int start = json.indexOf("\"" + key + "\":\"");
        if (start < 0) return "";
        start = json.indexOf('"', start + key.length() + 3) + 1;
        int end = json.indexOf('"', start);
        if (end < 0) return json.substring(start);
        return json.substring(start, end);
    }

    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "\n... [truncated]";
    }

    /**
     * Immutable record of a successful mutation.
     */
    public record MutationRecord(String moduleName, String version, double fitness,
                                  double delta, String timestamp,
                                  String originalSource, String mutatedSource) {}
}
