package de.metis.modules.evolution;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Auto-discovers and selects Ollama models by task type.
 * <p>
 * Queries {@code /api/tags} on startup to discover available models,
 * then selects the best model for each task using heuristics:
 * <ul>
 *   <li><b>planning:</b> strong reasoning, medium size (12-32B params)</li>
 *   <li><b>mutation:</b> excellent code generation, larger context (14-35B)</li>
 *   <li><b>embedding:</b> small, fast, good embeddings (1-5B)</li>
 * </ul>
 * <p>
 * Selection prefers known-good model families and falls back to
 * largest available if no heuristic matches.
 */
public class ModelRegistry {

    private static final Logger LOG = Logger.getLogger(ModelRegistry.class.getName());

    private final String ollamaUrl;
    private final HttpClient http;
    private List<ModelInfo> availableModels = List.of();
    private String selectedPlanningModel;
    private String selectedMutationModel;
    private String selectedEmbeddingModel;

    // ── Known-good model families (prefix match against model name) ─────

    /** Reasoning-capable models (have thinking/chain-of-thought modes). */
    private static final List<String> REASONING_FAMILIES = List.of(
            "deepseek-r1",      // CoT-native, strongest reasoning
            "phi4-reasoning",   // CoT-native
            "lfm2",             // 12h-Live-Test: planningEff 0.81, ½ Latenz von Mistral
            "qwen3.6",          // general + reasoning
            "phi4",             // strong reasoning
            "mistral-small3",   // fast reasoning (Fallback #1)
            "olmo-3",           // thinking model
            "nemotron",         // NVIDIA reasoning cascade
            "granite4",         // IBM reasoning
            "devstral",         // general purpose
            "laguna"            // general reasoning
    );

    /** Code-generation-capable models (structured output, large context). */
    private static final List<String> CODE_GEN_FAMILIES = List.of(
            "deepseek-r1",      // excellent code + reasoning (preferred for mutations)
            "qwen3.6",          // top code gen, large context
            "mistral-small3",   // reliable code output
            "granite4",         // code-trained
            "gemma4",           // capable code
            "nemotron",         // NVIDIA code generation
            "devstral",         // code-aware
            // lfm2:24b ist ein Reasoner, kein dedizierter Coder → nur in REASONING
            "laguna"            // general code
    );

    /** Embedding-capable models (small, good vector representations). Prefer dedicated embedding models. */
    private static final List<String> EMBEDDING_FAMILIES = List.of(
            "nomic-embed",      // dedicated embedding model, 768d, 0.3 GB — BEST
            "mxbai-embed",      // dedicated embedding model
            "all-minilm",        // dedicated embedding model
            "llama3.2",         // general purpose, 3072d — fallback
            "llama3.1"          // older fallback
    );

    // ── Size ranges (in GB) ─────────────────────────────────────────────

    private static final double MIN_PLANNING_GB = 8.0;
    private static final double MAX_PLANNING_GB = 40.0;
    private static final double MIN_MUTATION_GB = 12.0;
    private static final double MAX_MUTATION_GB = 42.0;
    private static final double MAX_EMBEDDING_GB = 5.0;

    // ── Defaults (used when discovery fails) ─────────────────────────────

    private static final String DEFAULT_PLANNING = "lfm2:24b";
    private static final String DEFAULT_MUTATION = "deepseek-r1:32b";
    private static final String DEFAULT_EMBEDDING = "nomic-embed-text";

    /**
     * Create registry connected to an Ollama instance.
     *
     * @param ollamaBaseUrl e.g. "http://192.168.22.204:11434"
     */
    public ModelRegistry(String ollamaBaseUrl) {
        this.ollamaUrl = ollamaBaseUrl.endsWith("/") 
                ? ollamaBaseUrl.substring(0, ollamaBaseUrl.length() - 1) 
                : ollamaBaseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Refresh model list from Ollama without restarting.
     * Re-discovers all models and updates selections.
     */
    public int refresh() {
        return discover().modelCount();
    }

    /**
     * Total number of known models (auto-selected + available).
     */
    public int modelCount() {
        return availableModels != null ? availableModels.size() : 0;
    }

    /**
     * Discover available models and auto-select best for each task.
     * Call once after construction.
     */
    public ModelRegistry discover() {
        try {
            availableModels = fetchAvailableModels();
            LOG.info("Discovered " + availableModels.size() + " Ollama models");

            selectedPlanningModel = selectBest(availableModels, this::isGoodForPlanning, REASONING_FAMILIES);
            selectedMutationModel = selectBest(availableModels, this::isGoodForMutation, CODE_GEN_FAMILIES);
            selectedEmbeddingModel = selectBest(availableModels, this::isGoodForEmbedding, EMBEDDING_FAMILIES);

            LOG.info("Auto-selected models:");
            LOG.info("  Planning:  " + selectedPlanningModel);
            LOG.info("  Mutation:  " + selectedMutationModel);
            LOG.info("  Embedding: " + selectedEmbeddingModel);
        } catch (Exception e) {
            LOG.warning("Model discovery failed: " + e.getMessage() + " — using defaults");
            selectedPlanningModel = DEFAULT_PLANNING;
            selectedMutationModel = DEFAULT_MUTATION;
            selectedEmbeddingModel = DEFAULT_EMBEDDING;
        }
        return this;
    }

    // ── Public accessors ─────────────────────────────────────────────────

    public String planningModel() { return selectedPlanningModel != null ? selectedPlanningModel : DEFAULT_PLANNING; }
    public String mutationModel() { return selectedMutationModel != null ? selectedMutationModel : DEFAULT_MUTATION; }
    public String embeddingModel() { return selectedEmbeddingModel != null ? selectedEmbeddingModel : DEFAULT_EMBEDDING; }
    public List<ModelInfo> availableModels() { return availableModels; }

    /** Manual override for testing. */
    public void overridePlanningModel(String model) { this.selectedPlanningModel = model; }
    public void overrideMutationModel(String model) { this.selectedMutationModel = model; }
    public void overrideEmbeddingModel(String model) { this.selectedEmbeddingModel = model; }

    /**
     * Prune a model from auto-selection registry.
     * Called by Watchdog when eval reports show consistent underperformance.
     * The pruned model will not be auto-selected until re-discovered.
     */
    public void pruneModel(String modelName) {
        if (modelName == null || modelName.isBlank()) return;
        String lower = modelName.toLowerCase();
        if (selectedPlanningModel != null && selectedPlanningModel.toLowerCase().contains(lower)) {
            LOG.warning("PRUNE: removing planning model " + selectedPlanningModel);
            selectedPlanningModel = null;
        }
        if (selectedMutationModel != null && selectedMutationModel.toLowerCase().contains(lower)) {
            LOG.warning("PRUNE: removing mutation model " + selectedMutationModel);
            selectedMutationModel = null;
        }
        if (selectedEmbeddingModel != null && selectedEmbeddingModel.toLowerCase().contains(lower)) {
            LOG.warning("PRUNE: removing embedding model " + selectedEmbeddingModel);
            selectedEmbeddingModel = null;
        }
        if (availableModels != null) {
            availableModels.removeIf(m -> m.name().toLowerCase().contains(lower));
        }
        LOG.info("Model pruned: " + modelName + " — will be re-evaluated on next discover()");
    }

    // ── Model info ───────────────────────────────────────────────────────

    public record ModelInfo(String name, long sizeBytes, String family) {
        public double sizeGb() { return sizeBytes / (1024.0 * 1024.0 * 1024.0); }
        @Override public String toString() {
            return String.format("%s (%.1f GB, family=%s)", name, sizeGb(), family);
        }
    }

    // ── Discovery ────────────────────────────────────────────────────────

    private List<ModelInfo> fetchAvailableModels() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl + "/api/tags"))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama returned " + response.statusCode());
        }

        return parseModels(response.body());
    }

    private List<ModelInfo> parseModels(String json) {
        List<ModelInfo> models = new ArrayList<>();

        // Parse JSON array of { "name": "...", "size": N, ... }
        String searchKey = "\"models\":[";
        int arrStart = json.indexOf(searchKey);
        if (arrStart < 0) return models;
        arrStart += searchKey.length();

        // Find array end
        int arrEnd = findMatchingBracket(json, arrStart - 1);
        if (arrEnd < 0) arrEnd = json.length();

        int pos = arrStart;
        while (pos < arrEnd) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0 || objStart >= arrEnd) break;

            // Find matching closing brace (handle nested objects like "details":{...})
            int objEnd = findMatchingBrace(json, objStart);
            if (objEnd < 0 || objEnd >= arrEnd) break;

            String obj = json.substring(objStart, objEnd + 1);

            String name = extractJsonString(obj, "name");
            long size = extractJsonLong(obj, "size");

            if (name != null && !name.isBlank()) {
                String family = classifyFamily(name);
                models.add(new ModelInfo(name, size, family));
            }

            pos = objEnd + 1;
        }

        return models;
    }

    /** Find the matching closing bracket/brace, counting nesting depth. */
    private int findMatchingBracket(String json, int openPos) {
        char open = json.charAt(openPos);
        char close = open == '[' ? ']' : open == '{' ? '}' : open;
        int depth = 0;
        boolean inString = false;
        for (int i = openPos; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (inString) continue;
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** Convenience: find matching brace from an opening brace. */
    private int findMatchingBrace(String json, int openBrace) {
        return findMatchingBracket(json, openBrace);
    }

    // ── Model selection ──────────────────────────────────────────────────

    private String selectBest(List<ModelInfo> models, Predicate<ModelInfo> criteria,
                              List<String> familyPriority) {
        // Prefer models that match the criteria, ranked by family priority then size
        var candidates = models.stream()
                .filter(criteria)
                .sorted(Comparator
                        .comparingInt((ModelInfo m) -> familyRank(m.name(), familyPriority))
                        .thenComparing(Comparator.comparingLong(ModelInfo::sizeBytes).reversed()))
                .toList();

        if (!candidates.isEmpty()) {
            return candidates.getFirst().name();
        }

        // Fallback: any model that roughly fits, by size
        return models.stream()
                .max(Comparator.comparingLong(ModelInfo::sizeBytes))
                .map(ModelInfo::name)
                .orElse(null);
    }

    /** Lower rank = higher priority. Models not in any family get rank 999. */
    private static int familyRank(String modelName, List<String> families) {
        String lower = modelName.toLowerCase();
        for (int i = 0; i < families.size(); i++) {
            if (lower.contains(families.get(i).toLowerCase())) return i;
        }
        return 999;
    }

    private boolean isGoodForPlanning(ModelInfo m) {
        double gb = m.sizeGb();
        if (gb < MIN_PLANNING_GB || gb > MAX_PLANNING_GB) return false;

        String name = m.name().toLowerCase();
        // Skip pure embedding models
        if (name.contains("embed") || name.contains("nomic")) return false;
        // Skip med-specific models
        if (name.contains("medgemma")) return false;

        // Family match is preferred
        for (String f : REASONING_FAMILIES) {
            if (name.contains(f.toLowerCase())) return true;
        }

        // Also accept general models of appropriate size
        return gb >= 12.0 && gb <= 35.0;
    }

    private boolean isGoodForMutation(ModelInfo m) {
        double gb = m.sizeGb();
        if (gb < MIN_MUTATION_GB || gb > MAX_MUTATION_GB) return false;

        String name = m.name().toLowerCase();
        if (name.contains("embed") || name.contains("nomic")) return false;
        if (name.contains("medgemma")) return false;

        for (String f : CODE_GEN_FAMILIES) {
            if (name.contains(f.toLowerCase())) return true;
        }

        // Accept any large model
        return gb >= 16.0;
    }

    private boolean isGoodForEmbedding(ModelInfo m) {
        double gb = m.sizeGb();
        if (gb > MAX_EMBEDDING_GB) return false;

        String name = m.name().toLowerCase();

        for (String f : EMBEDDING_FAMILIES) {
            if (name.contains(f.toLowerCase())) return true;
        }

        // Accept any small model that's not a reasoning/code specialist
        boolean isSpecialist = false;
        for (String f : REASONING_FAMILIES) {
            if (name.contains(f.toLowerCase())) { isSpecialist = true; break; }
        }
        for (String f : CODE_GEN_FAMILIES) {
            if (name.contains(f.toLowerCase())) { isSpecialist = true; break; }
        }
        return !isSpecialist && gb <= 3.0;
    }

    private String classifyFamily(String modelName) {
        String lower = modelName.toLowerCase();
        for (String family : REASONING_FAMILIES) {
            if (lower.contains(family.toLowerCase())) return family;
        }
        for (String family : CODE_GEN_FAMILIES) {
            if (lower.contains(family.toLowerCase())) return family;
        }
        for (String family : EMBEDDING_FAMILIES) {
            if (lower.contains(family.toLowerCase())) return family;
        }
        return "unknown";
    }

    // ── JSON extraction helpers ──────────────────────────────────────────

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();

        StringBuilder val = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"' -> { val.append('"'); i++; }
                    case '\\' -> { val.append('\\'); i++; }
                    case 'n' -> { val.append('\n'); i++; }
                    case 't' -> { val.append('\t'); i++; }
                    default -> val.append(c);
                }
            } else if (c == '"') {
                break;
            } else {
                val.append(c);
            }
        }
        return val.toString();
    }

    private long extractJsonLong(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return 0;
        start += search.length();

        // Skip whitespace
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        // Read number
        StringBuilder num = new StringBuilder();
        while (start < json.length() && Character.isDigit(json.charAt(start))) {
            num.append(json.charAt(start++));
        }
        try {
            return Long.parseLong(num.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
