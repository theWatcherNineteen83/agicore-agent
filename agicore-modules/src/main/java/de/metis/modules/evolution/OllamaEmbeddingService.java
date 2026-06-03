package de.metis.modules.evolution;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Calls Ollama's embedding API to vectorize text.
 * <p>
 * Uses nomic-embed-text (768-dim) or falls back to llama3.2:3b (3072-dim).
 * Model is resolved via ModelRegistry; dimension is auto-detected on first call.
 * <p>
 * Includes a bounded LRU cache (default 4096 entries) keyed by SHA-256 of the
 * full input text — prevents the previous unbounded ConcurrentHashMap from
 * leaking memory with high belief volume (5.700+ beliefs) and avoids cache
 * collisions from prefix-truncated keys.
 */
public class OllamaEmbeddingService {

    private static final Logger LOG = Logger.getLogger(OllamaEmbeddingService.class.getName());

    private static final String OLLAMA_URL = "http://192.168.22.204:11434/api/embeddings";
    private static final String DEFAULT_MODEL = "nomic-embed-text"; // 768-dim, preferred
    private static final String FALLBACK_MODEL = "llama3.2:3b";     // 3072-dim, legacy
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_CACHE_SIZE = 4096;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String model;
    private final int cacheCapacity;
    private int embeddingDimension = -1; // auto-detected on first call

    /** Bounded LRU cache keyed by content hash. Synchronized for thread safety. */
    private final Map<String, double[]> cache;
    private volatile int embedCount = 0;
    private volatile int cacheHits = 0;
    private volatile int cacheEvictions = 0;
    private volatile int serviceUnavailable = 0;

    // ── Circuit breaker ───────────────────────────────────────────────
    // After N consecutive 503s, stop calling the API for a cooldown period
    // to avoid flooding Ollama's request queue (which caused 103+ 503s on 2026-06-02).
    private static final int CB_FAILURE_THRESHOLD = 5;
    private static final long CB_COOLDOWN_MS = 60_000; // 1 minute
    private volatile int consecutive503s = 0;
    private volatile long circuitOpenUntil = 0;
    private volatile int circuitOpenCount = 0;
    private volatile int requestsSkipped = 0;

    public OllamaEmbeddingService() {
        this(DEFAULT_MODEL, DEFAULT_CACHE_SIZE);
    }

    public OllamaEmbeddingService(String model) {
        this(model, DEFAULT_CACHE_SIZE);
    }

    public OllamaEmbeddingService(String model, int cacheCapacity) {
        this.model = model != null ? model : DEFAULT_MODEL;
        this.cacheCapacity = Math.max(64, cacheCapacity);
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(this.cacheCapacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, double[]> eldest) {
                boolean evict = size() > OllamaEmbeddingService.this.cacheCapacity;
                if (evict) cacheEvictions++;
                return evict;
            }
        });
    }

    /** Factory method: auto-select best embedding model from registry. */
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
     * @param text the text to embed (full text is hashed for cache key,
     *             then truncated to ~200 chars for the API call to bound tokens)
     * @return embedding vector, or a zero vector on failure
     */
    /**
     * Returns true if the circuit breaker is currently open (cooldown active),
     * meaning we should skip embedding API calls entirely.
     */
    public boolean circuitOpen() {
        return circuitOpenUntil > 0 && System.currentTimeMillis() < circuitOpenUntil;
    }

    public double[] embed(String text) {
        if (text == null) return new double[0];

        // Cache key = SHA-256 of full text (no prefix collisions)
        String key = sha256(text);

        double[] cached = cache.get(key);
        if (cached != null) {
            cacheHits++;
            return cached;
        }

        // ── Circuit breaker: skip API call during cooldown ─────────
        if (circuitOpen()) {
            requestsSkipped++;
            return new double[0];
        }

        // API input is still truncated to bound token usage
        String apiInput = text.length() > 200 ? text.substring(0, 200) : text;

        try {
            String jsonBody = String.format("""
                    {"model": "%s", "prompt": %s, "options": {"num_gpu": 0}}
                    """, model, escapeJson(apiInput));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_URL))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 503) {
                // Retry with exponential backoff (Ollama queue saturation)
                for (int retry = 0; retry < 3; retry++) {
                    long backoff = (long) (1000 * Math.pow(2, retry));
                    Thread.sleep(backoff);
                    response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) break;
                }
            }
            if (response.statusCode() != 200) {
                serviceUnavailable++;
                consecutive503s++;
                LOG.warning("Embedding API returned " + response.statusCode()
                        + " (total 503s: " + serviceUnavailable
                        + ", consecutive: " + consecutive503s + ")");

                // Open circuit breaker when consecutive failures exceed threshold
                if (consecutive503s >= CB_FAILURE_THRESHOLD) {
                    circuitOpenUntil = System.currentTimeMillis() + CB_COOLDOWN_MS;
                    circuitOpenCount++;
                    LOG.warning("Embedding circuit breaker OPEN for " + (CB_COOLDOWN_MS / 1000)
                            + "s (" + consecutive503s + " consecutive 503s, trip #" + circuitOpenCount + ")");
                }
                return new double[0];
            }

            // Success — reset circuit breaker
            consecutive503s = 0;
            if (circuitOpenUntil > 0) {
                LOG.info("Embedding circuit breaker CLOSED (API healthy)");
                circuitOpenUntil = 0;
            }

            double[] vector = parseEmbedding(response.body());
            if (vector.length > 0) {
                if (embeddingDimension < 0) {
                    embeddingDimension = vector.length;
                    LOG.info("Embedding dimension detected: " + embeddingDimension + " (model: " + model + ")");
                }
                cache.put(key, vector);
                embedCount++;
            }
            return vector;

        } catch (Exception e) {
            consecutive503s++;
            if (consecutive503s >= CB_FAILURE_THRESHOLD) {
                circuitOpenUntil = System.currentTimeMillis() + CB_COOLDOWN_MS;
                circuitOpenCount++;
                LOG.warning("Embedding circuit breaker OPEN for " + (CB_COOLDOWN_MS / 1000)
                        + "s after exception: " + e.getMessage());
            }
            LOG.warning("Embedding failed: " + e.getMessage());
            return new double[0];
        }
    }

    private double[] parseEmbedding(String json) {
        int start = json.indexOf("\"embedding\":[");
        if (start < 0) return new double[0];
        start += 13;
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

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    public int embedCount() { return embedCount; }
    public int cacheHits() { return cacheHits; }
    public int cacheEvictions() { return cacheEvictions; }
    public int serviceUnavailable() { return serviceUnavailable; }
    public int cacheSize() { return cache.size(); }
    public double cacheHitRate() {
        int total = embedCount + cacheHits;
        return total == 0 ? 0.0 : (double) cacheHits / total;
    }
    public int consecutive503s() { return consecutive503s; }
    public int circuitOpenCount() { return circuitOpenCount; }
    public int requestsSkipped() { return requestsSkipped; }
}
