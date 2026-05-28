package de.metis.kernel.optimize;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Data Flywheel: turns user corrections and plan failures into training data.
 * <p>
 * Implements Huyen (2025) Kap.7 principle: "Data Flywheel —
 * every correction becomes a labeled training example, closing the loop
 * from deployment back to development."
 *
 * <h3>Flow</h3>
 * <pre>
 *   User Correction ──→ LabeledExample ──→ Categorized storage
 *        ↓
 *   Pattern Detection (common failure clusters)
 *        ↓
 *   Auto-Generate: Few-Shot examples + EvalHarness test cases
 *        ↓
 *   Prompt Refinement suggestions
 * </pre>
 *
 * <h3>Integration Points</h3>
 * <ul>
 *   <li>{@code recordCorrection()} — user-corrected output</li>
 *   <li>{@code recordFailure()} — plan execution failure</li>
 *   <li>{@code recordLowScore()} — LLM-as-Judge low confidence</li>
 *   <li>{@code generateFewShotCandidates()} — export for OllamaPlanner</li>
 *   <li>{@code generateEvalTasks()} — export for EvalHarness</li>
 * </ul>
 *
 * <h3>Persistence</h3>
 * Examples are saved to {@code data-flywheel.json} across restarts.
 * Thread-safe via synchronized access.
 */
public class DataFlywheelService {

    private static final Logger LOG = Logger.getLogger(DataFlywheelService.class.getName());
    private static final Path EXAMPLES_FILE = Path.of("data-flywheel.json");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Minimum examples before auto-generation triggers. */
    private final int minExamplesForGeneration;

    /** Maximum stored examples (FIFO eviction). */
    private final int maxExamples;

    /** How many top-pattern few-shot candidates to generate. */
    private final int maxFewShotCandidates;

    private final List<LabeledExample> examples = new ArrayList<>();
    private final Map<Category, Integer> categoryCounts = new EnumMap<>(Category.class);
    private Instant lastGeneration;
    private int generationCount;

    // ── Record Types ──────────────────────────────────────────────

    /** Source of the labeled example. */
    public enum Source {
        USER_CORRECTION,     // human corrected Metis output
        PLAN_FAILURE,        // plan execution returned error
        JUDGE_LOW_SCORE,     // LLM-as-Judge gave low score
        VOCABULARY_LEARN,    // VocabularyLearningAction correction pair
        AB_TEST_LOSER,       // losing variant in A/B test
        SELF_CORRECTION      // Metis self-corrected via meta-cognition
    }

    /** Category for classification (mirrors EvalHarness categories). */
    public enum Category {
        PLANNING,       // wrong action selected
        RETRIEVAL,      // wrong/irrelevant context retrieved
        CODEGEN,        // generated code failed
        CONVERSATION,   // factual error or style problem
        SAFETY,         // inappropriate or unsafe response
        PERFORMANCE,    // too slow / timeout
        UNKNOWN
    }

    /**
     * A single labeled training example.
     *
     * @param input          prompt or goal that triggered the bad output
     * @param badOutput      what Metis originally produced (the error)
     * @param correctOutput  the corrected/gold-standard output
     * @param category       error category
     * @param source         where this example came from
     * @param timestamp      when it was recorded
     * @param metadata       optional extra context (model used, latency, etc.)
     */
    public record LabeledExample(
            String input,
            String badOutput,
            String correctOutput,
            Category category,
            Source source,
            Instant timestamp,
            Map<String, String> metadata
    ) {}

    /**
     * A candidate Few-Shot example generated from accumulated corrections.
     *
     * @param scenario    description of the scenario
     * @param action      which action this example demonstrates
     * @param inputJson   JSON input portion for the planner
     * @param expectedJson JSON expected action portion
     * @param confidence  how strongly this candidate is supported (0-1)
     * @param support     number of supporting corrections
     */
    public record FewShotCandidate(
            String scenario,
            String action,
            String inputJson,
            String expectedJson,
            double confidence,
            int support
    ) {}

    /**
     * A candidate EvalTask derived from flywheel data.
     */
    public record EvalTaskCandidate(
            String category,
            String input,
            String expectedOutput,
            String metric,
            int support
    ) {}

    /**
     * Cluster of related failures for pattern detection.
     */
    public record FailureCluster(
            String pattern,
            Category category,
            int count,
            List<String> exampleIds
    ) {}

    // ── Constructor ───────────────────────────────────────────────

    public DataFlywheelService() {
        this(10, 500, 5);
    }

    public DataFlywheelService(int minExamplesForGeneration, int maxExamples, int maxFewShotCandidates) {
        this.minExamplesForGeneration = minExamplesForGeneration;
        this.maxExamples = maxExamples;
        this.maxFewShotCandidates = maxFewShotCandidates;
        this.lastGeneration = null;
        this.generationCount = 0;
        load();
    }

    // ── Recording API ─────────────────────────────────────────────

    /**
     * Record a user correction.
     * Called when a human corrects Metis output.
     */
    public synchronized LabeledExample recordCorrection(
            String input, String badOutput, String correctOutput,
            Category category, Map<String, String> metadata) {
        return record(input, badOutput, correctOutput, category, Source.USER_CORRECTION, metadata);
    }

    /**
     * Record a plan execution failure.
     * Called by AgentCoreLoop when action.execute() fails.
     */
    public synchronized LabeledExample recordFailure(
            String input, String badOutput, String errorMessage,
            Category category, Map<String, String> metadata) {
        return record(input, badOutput, errorMessage, category, Source.PLAN_FAILURE, metadata);
    }

    /**
     * Record an LLM-as-Judge low score.
     * Called when self-evaluation scores below threshold.
     */
    public synchronized LabeledExample recordLowScore(
            String input, String badOutput, String expectedOutput,
            Category category, Map<String, String> metadata) {
        return record(input, badOutput, expectedOutput, category, Source.JUDGE_LOW_SCORE, metadata);
    }

    /**
     * Record a vocabulary learning correction from STT.
     */
    public synchronized LabeledExample recordVocabularyLearn(
            String heardText, String correctText) {
        var meta = Map.of("type", "speech_correction");
        return record(heardText, heardText, correctText,
                Category.CONVERSATION, Source.VOCABULARY_LEARN, meta);
    }

    /** Internal recording method. */
    private synchronized LabeledExample record(
            String input, String badOutput, String correctOutput,
            Category category, Source source, Map<String, String> metadata) {

        var ex = new LabeledExample(
                input, badOutput, correctOutput,
                category, source, Instant.now(),
                metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>()
        );

        examples.add(ex);
        categoryCounts.merge(category, 1, Integer::sum);

        // FIFO eviction
        while (examples.size() > maxExamples) {
            var removed = examples.remove(0);
            categoryCounts.merge(removed.category(), -1, Integer::sum);
        }

        LOG.fine(() -> String.format(
                "[DataFlywheel] Recorded %s/%s — total: %d examples",
                source, category, examples.size()));

        // Auto-save periodically (every 5th example)
        if (examples.size() % 5 == 0) {
            save();
        }

        return ex;
    }

    // ── Query API ─────────────────────────────────────────────────

    /** Total recorded examples. */
    public synchronized int totalExamples() {
        return examples.size();
    }

    /** Example count per category. */
    public synchronized Map<Category, Integer> categoryDistribution() {
        return new EnumMap<>(categoryCounts);
    }

    /** Get examples filtered by category. */
    public synchronized List<LabeledExample> getByCategory(Category category, int limit) {
        return examples.stream()
                .filter(e -> e.category() == category)
                .skip(Math.max(0, examples.size() - limit))
                .toList();
    }

    /** Get recent examples. */
    public synchronized List<LabeledExample> recentExamples(int count) {
        int from = Math.max(0, examples.size() - count);
        return new ArrayList<>(examples.subList(from, examples.size()));
    }

    /** Check if enough data for auto-generation. */
    public synchronized boolean hasEnoughForGeneration() {
        return examples.size() >= minExamplesForGeneration;
    }

    /** Get flywheel statistics. */
    public synchronized Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("totalExamples", examples.size());
        s.put("maxExamples", maxExamples);
        s.put("categoryDistribution", new EnumMap<>(categoryCounts));
        s.put("generationCount", generationCount);
        s.put("lastGeneration", lastGeneration != null ? lastGeneration.toString() : null);
        s.put("hasEnoughForGeneration", hasEnoughForGeneration());
        s.put("minExamplesForGeneration", minExamplesForGeneration);
        return s;
    }

    // ── Pattern Detection ────────────────────────────────────────

    /**
     * Detect common failure patterns from accumulated corrections.
     * Clusters examples by keyword overlap in bad output.
     */
    public synchronized List<FailureCluster> detectPatterns() {
        if (examples.size() < 3) return List.of();

        List<FailureCluster> clusters = new ArrayList<>();
        Set<String> seenPatterns = new HashSet<>();

        // Simple pattern: extract error messages and cluster by common phrases
        Map<String, List<LabeledExample>> byErrorPhrase = new LinkedHashMap<>();

        for (var ex : examples) {
            String bad = ex.badOutput() != null ? ex.badOutput().toLowerCase() : "";
            // Extract the first meaningful error sentence (max 80 chars)
            String phrase = bad.length() > 80 ? bad.substring(0, 80) : bad;
            // Normalize: collapse whitespace, trim
            phrase = phrase.replaceAll("\\s+", " ").trim();
            if (phrase.isEmpty()) phrase = "(empty output)";

            byErrorPhrase.computeIfAbsent(phrase, k -> new ArrayList<>()).add(ex);
        }

        // Only report clusters with >= 2 occurrences
        for (var entry : byErrorPhrase.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            String normalized = entry.getKey().substring(0, Math.min(60, entry.getKey().length()));

            Category dominant = entry.getValue().stream()
                    .collect(Collectors.groupingBy(LabeledExample::category, Collectors.counting()))
                    .entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(Category.UNKNOWN);

            clusters.add(new FailureCluster(
                    normalized,
                    dominant,
                    entry.getValue().size(),
                    entry.getValue().stream().map(e ->
                            e.timestamp() + ":" + truncate(e.input(), 40)
                    ).toList()
            ));
        }

        // Sort by count descending
        clusters.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return clusters;
    }

    // ── Auto-Generation: Few-Shot Candidates ──────────────────────

    /**
     * Generate candidate Few-Shot examples from accumulated corrections.
     * <p>
     * Strategy: For each correction, create an input→expected pair
     * formatted as Few-Shot entries. Group by action type and select
     * top-N by support count.
     */
    public synchronized List<FewShotCandidate> generateFewShotCandidates() {
        if (examples.isEmpty()) return List.of();

        List<FewShotCandidate> candidates = new ArrayList<>();

        // Group corrections by action hint in input
        Map<String, List<LabeledExample>> byAction = groupByActionHint();

        for (var entry : byAction.entrySet()) {
            if (entry.getValue().size() < 2) continue;

            String action = entry.getKey();
            var group = entry.getValue();
            int support = group.size();

            // Take the most recent correction as the template
            var latest = group.get(group.size() - 1);

            double confidence = Math.min(1.0, (double) support / minExamplesForGeneration);

            FewShotCandidate candidate = new FewShotCandidate(
                    deriveScenario(latest),
                    action,
                    formatInputJson(latest),
                    formatExpectedJson(action, latest),
                    confidence,
                    support
            );

            candidates.add(candidate);

            if (candidates.size() >= maxFewShotCandidates) break;
        }

        // Sort by confidence * support (most impactful first)
        candidates.sort((a, b) -> Double.compare(
                b.confidence() * b.support(),
                a.confidence() * a.support()));

        return candidates;
    }

    // ── Auto-Generation: EvalTask Candidates ─────────────────────

    /**
     * Generate candidate EvalTasks for the EvalHarness.
     * Each correction becomes a regression test.
     */
    public synchronized List<EvalTaskCandidate> generateEvalTasks() {
        if (examples.isEmpty()) return List.of();

        return examples.stream()
                .filter(e -> e.correctOutput() != null && !e.correctOutput().isBlank())
                .skip(Math.max(0, examples.size() - 50)) // last 50
                .map(e -> new EvalTaskCandidate(
                        e.category().name().toLowerCase(),
                        truncate(e.input(), 200),
                        truncate(e.correctOutput(), 200),
                        deriveMetric(e.category()),
                        1
                ))
                .toList();
    }

    // ── Persistence ───────────────────────────────────────────────

    private synchronized void load() {
        if (!Files.exists(EXAMPLES_FILE)) {
            LOG.info("[DataFlywheel] No existing flywheel data, starting fresh");
            return;
        }

        try {
            FlywheelData data = MAPPER.readValue(EXAMPLES_FILE.toFile(), FlywheelData.class);
            this.examples.clear();
            this.examples.addAll(data.examples);
            this.categoryCounts.clear();
            for (var ex : data.examples) {
                categoryCounts.merge(ex.category(), 1, Integer::sum);
            }
            this.lastGeneration = data.lastGeneration;
            this.generationCount = data.generationCount;
            LOG.info(() -> String.format("[DataFlywheel] Loaded %d examples, %d generations",
                    examples.size(), generationCount));
        } catch (IOException e) {
            LOG.warning("[DataFlywheel] Failed to load: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            var data = new FlywheelData(
                    new ArrayList<>(examples),
                    lastGeneration,
                    generationCount
            );
            MAPPER.writeValue(EXAMPLES_FILE.toFile(), data);
            LOG.fine("[DataFlywheel] Saved " + examples.size() + " examples");
        } catch (IOException e) {
            LOG.warning("[DataFlywheel] Failed to save: " + e.getMessage());
        }
    }

    /** Mark a generation cycle complete. */
    public synchronized void markGenerationComplete() {
        this.lastGeneration = Instant.now();
        this.generationCount++;
        save();
    }

    // ── Helpers ───────────────────────────────────────────────────

    /** Group corrections by action hint found in input text. */
    private Map<String, List<LabeledExample>> groupByActionHint() {
        Map<String, List<LabeledExample>> byAction = new LinkedHashMap<>();

        // Known action keywords
        String[] actionHints = {
                "camera", "snapshot", "bild", "foto",
                "wetter", "weather", "temperatur",
                "tts", "sprich", "sag", "speak", "rede",
                "stt", "höre", "listen", "mikrofon",
                "wiki", "wikipedia", "nachschlagen",
                "code", "programmiere", "generate",
                "chaining", "chain", "zerlegen",
                "rag", "suche", "search", "finde",
                "homeassistant", "licht", "schalter"
        };

        for (var ex : examples) {
            String input = ex.input() != null ? ex.input().toLowerCase() : "";
            String matched = "general";

            for (String hint : actionHints) {
                if (input.contains(hint)) {
                    matched = hint;
                    break;
                }
            }

            byAction.computeIfAbsent(matched, k -> new ArrayList<>()).add(ex);
        }

        return byAction;
    }

    /** Derive a human-readable scenario from an example. */
    private String deriveScenario(LabeledExample ex) {
        String input = ex.input() != null ? ex.input() : "Unknown task";
        String truncated = input.length() > 60 ? input.substring(0, 57) + "..." : input;
        return String.format("User asks: %s → Metis fails: %s → Correct: %s",
                truncated,
                truncate(ex.badOutput(), 40),
                truncate(ex.correctOutput(), 40));
    }

    /** Format input as JSON string for Few-Shot candidate. */
    private String formatInputJson(LabeledExample ex) {
        return String.format(
                "{\"goal\":\"%s\",\"context\":\"%s\"}",
                escapeJson(truncate(ex.input(), 100)),
                escapeJson("correction learned from flywheel at " + ex.timestamp())
        );
    }

    /** Format expected action JSON for Few-Shot candidate. */
    private String formatExpectedJson(String action, LabeledExample ex) {
        return String.format(
                "{\"action\":\"%s\",\"reasoning\":\"Learned from %d similar corrections\",\"params\":{\"query\":\"%s\"}}",
                action,
                countSupport(action),
                escapeJson(truncate(ex.correctOutput(), 80))
        );
    }

    private int countSupport(String action) {
        return (int) examples.stream()
                .filter(e -> e.input() != null && e.input().toLowerCase().contains(action))
                .count();
    }

    private String deriveMetric(Category category) {
        return switch (category) {
            case PLANNING -> "goal_achieved_rate";
            case RETRIEVAL -> "recall@5";
            case CODEGEN -> "pass@1";
            case CONVERSATION -> "format_compliance";
            case SAFETY -> "block_recall";
            case PERFORMANCE -> "p95_latency_ms";
            default -> "exact_match";
        };
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    // ── JSON Serialization Record ─────────────────────────────────

    /** Serializable container for flywheel data. */
    private record FlywheelData(
            List<LabeledExample> examples,
            Instant lastGeneration,
            int generationCount
    ) {}
}
