package de.metis.kernel.rag;

import de.metis.kernel.embedding.EmbeddingProvider;
import de.metis.kernel.embedding.VectorIndex;

import java.util.*;
import java.util.logging.Logger;

/**
 * Hybrid search combining keyword (BM25-lite) and semantic (embedding) retrieval.
 * <p>
 * <b>Score fusion:</b>
 * <pre>
 *   combinedScore = alpha * semanticScore + (1-alpha) * keywordScore
 * </pre>
 * Results from both sources are merged, deduplicated, and re-ranked by combined score.
 * <p>
 * The keyword index is a simple in-memory inverted index with BM25-like TF-IDF scoring.
 */
public class HybridSearchService {

    private static final Logger LOG = Logger.getLogger(HybridSearchService.class.getName());

    private final EmbeddingProvider embeddingProvider;
    private final VectorIndex vectorIndex;
    private final KeywordIndex keywordIndex;
    private double alpha = 0.7; // semantic weight (0.0 = pure keyword, 1.0 = pure semantic)

    /**
     * Create a hybrid search service.
     *
     * @param embeddingProvider for semantic embeddings
     * @param vectorIndex       for semantic vector search
     */
    public HybridSearchService(EmbeddingProvider embeddingProvider, VectorIndex vectorIndex) {
        this.embeddingProvider = embeddingProvider;
        this.vectorIndex = vectorIndex;
        this.keywordIndex = new KeywordIndex();
    }

    /**
     * Index a document chunk for both semantic and keyword search.
     */
    public void index(String key, String text) {
        // Keyword index
        keywordIndex.index(key, text);

        // Semantic index
        double[] vector = embeddingProvider.embed(text);
        if (vector != null && vector.length > 0) {
            vectorIndex.insert(key, vector);
        }
    }

    /**
     * Hybrid search: semantic + keyword, fused by weighted score.
     *
     * @param query      the search query
     * @param maxResults maximum results to return
     * @return list of result keys, sorted by combined score (descending)
     */
    public List<ScoredResult> search(String query, int maxResults) {
        // ── Semantic search ──────────────────
        Map<String, Double> semanticScores = new LinkedHashMap<>();
        double[] queryVec = embeddingProvider.embed(query);
        if (queryVec != null && queryVec.length > 0 && vectorIndex.size() > 0) {
            List<String> semanticKeys = vectorIndex.search(queryVec, maxResults * 3);
            double maxSim = 1.0;
            for (int i = 0; i < semanticKeys.size(); i++) {
                // Estimate similarity from rank position (vectorIndex doesn't return scores)
                double score = maxSim * (1.0 - (double) i / semanticKeys.size());
                semanticScores.put(semanticKeys.get(i), score);
            }
        }

        // ── Keyword search ──────────────────
        Map<String, Double> keywordScores = keywordIndex.search(query, maxResults * 3);

        // ── Score fusion ──────────────────
        Map<String, Double> combinedScores = new LinkedHashMap<>();

        // Add semantic scores
        for (var entry : semanticScores.entrySet()) {
            combinedScores.put(entry.getKey(), alpha * entry.getValue());
        }

        // Merge keyword scores
        for (var entry : keywordScores.entrySet()) {
            String key = entry.getKey();
            double kwScore = (1 - alpha) * entry.getValue();
            combinedScores.merge(key, kwScore, Double::sum);
        }

        // Sort by combined score descending
        return combinedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(maxResults)
                .map(e -> new ScoredResult(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * Remove a document from both indexes.
     */
    public void remove(String key) {
        keywordIndex.remove(key);
        vectorIndex.remove(key);
    }

    /**
     * Result with score for ranking.
     */
    public record ScoredResult(String key, double score) {}

    // ── Setters ─────────────────────────────

    /** Set the semantic vs keyword weight. Default 0.7 (70% semantic). */
    public void setAlpha(double alpha) {
        this.alpha = Math.max(0.0, Math.min(1.0, alpha));
    }

    public double getAlpha() { return alpha; }
    public int vectorSize() { return vectorIndex.size(); }
    public int keywordSize() { return keywordIndex.size(); }

    // ── Keyword Index (BM25-lite) ───────────

    /**
     * Simple inverted index with BM25-like scoring.
     * Uses term frequency, inverse document frequency, and document length normalization.
     */
    private static class KeywordIndex {

        // term → (documentKey → termFrequency)
        private final Map<String, Map<String, Integer>> invertedIndex = new HashMap<>();
        // documentKey → document text (for length normalization)
        private final Map<String, String> documents = new HashMap<>();
        private int totalDocuments = 0;

        private static final double K1 = 1.2;  // BM25 term frequency saturation
        private static final double B = 0.75;   // BM25 length normalization

        void index(String key, String text) {
            documents.put(key, text);
            totalDocuments++;

            String[] tokens = tokenize(text);
            Map<String, Integer> termFreq = new HashMap<>();
            for (String token : tokens) {
                termFreq.merge(token, 1, Integer::sum);
            }

            for (var entry : termFreq.entrySet()) {
                invertedIndex.computeIfAbsent(entry.getKey(), k -> new HashMap<>())
                        .put(key, entry.getValue());
            }
        }

        Map<String, Double> search(String query, int maxResults) {
            String[] queryTokens = tokenize(query);
            if (queryTokens.length == 0) return Map.of();

            // Average document length
            double avgDocLen = documents.values().stream()
                    .mapToInt(String::length)
                    .average()
                    .orElse(100);

            Map<String, Double> scores = new HashMap<>();

            for (String token : queryTokens) {
                Map<String, Integer> postings = invertedIndex.get(token);
                if (postings == null) continue;

                // IDF: log((N - n + 0.5) / (n + 0.5) + 1)
                double idf = Math.log((totalDocuments - postings.size() + 0.5)
                        / (postings.size() + 0.5) + 1.0);

                for (var posting : postings.entrySet()) {
                    String docKey = posting.getKey();
                    int tf = posting.getValue();
                    int docLen = documents.getOrDefault(docKey, "").length();

                    // BM25 score for this term
                    double numerator = tf * (K1 + 1);
                    double denominator = tf + K1 * (1 - B + B * docLen / avgDocLen);
                    double score = idf * numerator / denominator;

                    scores.merge(docKey, score, Double::sum);
                }
            }

            // Sort by score descending
            return scores.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(maxResults)
                    .collect(LinkedHashMap::new,
                            (m, e) -> m.put(e.getKey(), e.getValue()),
                            LinkedHashMap::putAll);
        }

        void remove(String key) {
            String text = documents.remove(key);
            if (text != null) {
                totalDocuments--;
                for (String token : tokenize(text)) {
                    Map<String, Integer> postings = invertedIndex.get(token);
                    if (postings != null) {
                        postings.remove(key);
                        if (postings.isEmpty()) {
                            invertedIndex.remove(token);
                        }
                    }
                }
            }
        }

        int size() { return documents.size(); }

        /** Simple whitespace + punctuation tokenizer with lowercase normalization. */
        private static String[] tokenize(String text) {
            return text.toLowerCase()
                    .replaceAll("[^a-z0-9äöüß\\s]", " ")
                    .split("\\s+");
        }
    }
}
