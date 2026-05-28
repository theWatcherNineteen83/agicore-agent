package de.metis.modules.evolution;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Calls Ollama's embedding API to vectorize text.
 * <p>
 * Uses nomic-embed-text (768-dim) or falls back to llama3.2:3b (3072-dim).
 * Model is resolved via ModelRegistry; dimension is auto-detected on first call.
 * Results are cached to avoid redundant API calls.
 */
public class OllamaEmbeddingService {

    private static final Logger LOG = Logger.getLogger(OllamaEmbeddingService.class.getName());

    private static final String OLLAMA_URL = "http://192.168.22.204:11434/api/embeddings";
    private static final String DEFAULT_MODEL = "nomic-embed-text"; // 768-dim, preferred
    private static final String FALLBACK_MODEL = "llama3.2:3b";     // 3072-dim, legacy
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String model;
    private int embeddingDimension = -1; // auto-detected on first call

    /** Simple cache to avoid re-embedding the same text. */
    private final Map<String, double[]> cache = new ConcurrentHashMap<>();
    private int embedCount = 0;
    private int cacheHits = 0;

    /**
     * Create with default model (nomic-embed-text).
     */
    public OllamaEmbeddingService() {
        this(DEFAULT_MODEL);
    }

    /**
     * Create with a specific model. Use {@link #withModelRegistry(ModelRegistry)} for auto-selection.
     */
    public OllamaEmbeddingService(String model) {
        this.model = model != null ? model : DEFAULT_MODEL;
    }

    /**
     * Factory method: auto-select best embedding model from registry.
     */
    public static OllamaEmbeddingService withModelRegistry(ModelRegistry registry) {
        String model = registry.embeddingModel();
        LOG.info("EmbeddingService using model from registry: " + model);
        return new OllamaEmbeddingService(model);
    }

    public String model() { return model; }
    public int embeddingDimension() { return embeddingDimension; }

    /**
     * Generate an embedding vector for the given text.
     *
     * @param text the text to embed (truncated to ~500 chars)
     * @return embedding vector, or a zero vector on failure
     */
    public double[] embed(String text) {
        // Truncate to avoid excessive tokens
        String key = text.length() > 200 ? text.substring(0, 200) : text;

        // Cache check
        double[] cached = cache.get(key);
        if (cached != null) {
            cacheHits++;
            return cached;
        }

        try {
            String jsonBody = String.format("""
                    {"model": "%s", "prompt": %s}
                    """, model, escapeJson(key));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_URL))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.warning("Embedding API returned " + response.statusCode());
                return new double[0];
            }

            double[] vector = parseEmbedding(response.body());
            if (vector.length > 0) {
                if (embeddingDimension < 0) {
                    embeddingDimension = vector.length;
                    LOG.info("Embedding dimension detected: " + embeddingDimension + " (model: " + model + ")");
                }
                cache.put(key, vector);
                embedCount++;
                LOG.fine(() -> "Embedded " + vector.length + " dims for: " + key.substring(0, Math.min(50, key.length())));
            }
            return vector;

        } catch (Exception e) {
            LOG.warning("Embedding failed: " + e.getMessage());
            return new double[0];
        }
    }

    /** Parse the "embedding" array from Ollama's JSON response. */
    private double[] parseEmbedding(String json) {
        // Find "embedding":[...]
        int start = json.indexOf("\"embedding\":[");
        if (start < 0) return new double[0];
        start += 13; // skip "embedding":[
        int end = json.indexOf(']', start);
        if (end < 0) return new double[0];

        String[] parts = json.substring(start, end).split(",");
        double[] vec = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                vec[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                vec[i] = 0;
            }
        }
        return vec;
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    public int embedCount() { return embedCount; }
    public int cacheHits() { return cacheHits; }
}
