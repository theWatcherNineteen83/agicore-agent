package de.metis.modules;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Bootstraps Metis's WorldModel by querying one or more Ollama models.
 * <p>
 * Multi-model mode: queries all models for each seed question.
 * Answers that agree across models get boosted confidence (consensus).
 * Contradictory or unique answers get lower confidence (single-source).
 * <p>
 * Single model: {@code new KnowledgeBootstrap(url, List.of("phi4:latest"))}
 * Multi-model:  {@code new KnowledgeBootstrap(url, List.of("phi4:latest", "llama3.2:3b", "mistral-small3.1:24b"))}
 */
public class KnowledgeBootstrap {

    private static final Logger LOG = Logger.getLogger(KnowledgeBootstrap.class.getName());

    private static final List<String> SEED_QUESTIONS = List.of(
            "In one sentence: What are the most reliable shell commands on a Linux system?",
            "In one sentence: What should an AI agent know about HTTP requests?",
            "In one sentence: What is important about file system operations?",
            "In one sentence: What is the best approach to learning from experience?",
            "In one sentence: How should an AI handle uncertainty?",
            "In one sentence: What is the relationship between goals and actions?",
            "In one sentence: What does it mean to be self-aware as an AI?",
            "In one sentence: How should an evolving system balance stability and change?"
    );

    /** Minimum Jaccard similarity for two answers to be considered "agreeing". */
    private static final double AGREEMENT_THRESHOLD = 0.25;

    private final String ollamaUrl;
    private final List<String> models;
    private final HttpClient http;

    public KnowledgeBootstrap(String ollamaBaseUrl, List<String> models) {
        this.ollamaUrl = ollamaBaseUrl.endsWith("/") ? ollamaBaseUrl : ollamaBaseUrl;
        this.models = List.copyOf(models);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Query all models for each question. Build consensus beliefs.
     */
    public List<BeliefEntry> bootstrap() {
        List<BeliefEntry> beliefs = new ArrayList<>();
        LOG.info("Bootstrapping knowledge from " + models.size() + " model(s): " + models);

        for (String question : SEED_QUESTIONS) {
            // Collect answers from all models
            List<ModelAnswer> answers = new ArrayList<>();
            for (String model : models) {
                try {
                    String answer = askModel(question, model);
                    if (answer != null && !answer.isBlank()) {
                        String statement = extractStatement(answer);
                        double baseConfidence = assessConfidence(answer);
                        answers.add(new ModelAnswer(model, statement, baseConfidence));
                        LOG.fine("  " + model + " → " + truncate(statement, 60));
                    }
                } catch (Exception e) {
                    LOG.fine("  " + model + " failed: " + e.getMessage());
                }
            }

            if (answers.isEmpty()) continue;

            // Cluster similar answers
            List<List<ModelAnswer>> clusters = clusterAnswers(answers);

            // Generate beliefs from clusters
            for (var cluster : clusters) {
                // Use the most coherent answer as the belief statement
                String bestStatement = pickBestStatement(cluster);

                // Confidence: base + bonus for multi-model agreement + cluster size
                double consensusBonus = 0.0;
                if (models.size() > 1 && cluster.size() > 1) {
                    consensusBonus = 0.15 * (cluster.size() - 1); // +15% per additional model
                }
                double avgBaseConf = cluster.stream()
                        .mapToDouble(a -> a.confidence)
                        .average().orElse(0.6);
                double finalConfidence = Math.min(0.95, avgBaseConf + consensusBonus);

                String source = cluster.stream()
                        .map(a -> a.model)
                        .distinct()
                        .collect(Collectors.joining("+"));

                String sourceTag = "bootstrap:" + source + "(" + cluster.size() + "/" + models.size() + " agree)";

                if (models.size() > 1 && cluster.size() == 1) {
                    // Single-model opinion: penalize
                    finalConfidence *= 0.75;
                    sourceTag = "bootstrap:" + source + " (single source, unverified)";
                }

                beliefs.add(new BeliefEntry(bestStatement, finalConfidence, sourceTag));
                LOG.fine("  Belief: " + truncate(bestStatement, 60)
                        + " [" + String.format("%.0f%%", finalConfidence * 100)
                        + " from " + cluster.size() + "/" + models.size() + " models]");
            }
        }

        LOG.info("Bootstrapped " + beliefs.size() + " beliefs (consensus from "
                + models.size() + " model(s))");
        return beliefs;
    }

    // ── Model query ──────────────────────────────────────────────────

    private String askModel(String question, String model) throws Exception {
        String prompt = "Answer the following question with exactly ONE clear, factual sentence. "
                + "No explanations, no qualifiers. Just the answer.\n\n"
                + "Question: " + question + "\n\n"
                + "Answer:";

        String jsonBody = String.format("""
                {
                  "model": "%s",
                  "prompt": "%s",
                  "stream": false,
                  "options": {
                    "temperature": 0.3,
                    "num_predict": 120
                  }
                }
                """, model, escapeJson(prompt));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl + "/api/generate"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) return null;

        return extractResponseField(response.body());
    }

    // ── Consensus clustering ────────────────────────────────────────

    /**
     * Group similar answers using Jaccard similarity on word sets.
     */
    private List<List<ModelAnswer>> clusterAnswers(List<ModelAnswer> answers) {
        List<List<ModelAnswer>> clusters = new ArrayList<>();
        boolean[] assigned = new boolean[answers.size()];

        for (int i = 0; i < answers.size(); i++) {
            if (assigned[i]) continue;

            List<ModelAnswer> cluster = new ArrayList<>();
            cluster.add(answers.get(i));
            assigned[i] = true;

            for (int j = i + 1; j < answers.size(); j++) {
                if (assigned[j]) continue;
                if (jaccardSimilarity(answers.get(i).statement, answers.get(j).statement) >= AGREEMENT_THRESHOLD) {
                    cluster.add(answers.get(j));
                    assigned[j] = true;
                }
            }
            clusters.add(cluster);
        }
        return clusters;
    }

    /** Jaccard similarity: |A ∩ B| / |A ∪ B| */
    private double jaccardSimilarity(String a, String b) {
        Set<String> wordsA = wordSet(a);
        Set<String> wordsB = wordSet(b);
        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0;

        Set<String> intersection = new HashSet<>(wordsA);
        intersection.retainAll(wordsB);
        Set<String> union = new HashSet<>(wordsA);
        union.addAll(wordsB);

        return (double) intersection.size() / union.size();
    }

    private Set<String> wordSet(String text) {
        return Arrays.stream(text.toLowerCase().split("\\W+"))
                .filter(w -> w.length() > 3)
                .collect(Collectors.toSet());
    }

    /** Pick the cleanest statement from a cluster. */
    private String pickBestStatement(List<ModelAnswer> cluster) {
        return cluster.stream()
                .min(Comparator.comparingInt(a -> a.statement.length())) // prefer concise
                .map(a -> a.statement)
                .orElse(cluster.getFirst().statement);
    }

    // ── Text processing ──────────────────────────────────────────────

    private String extractStatement(String raw) {
        String cleaned = raw
                .replaceAll("^(Answer:|Response:|The answer is|I think|I believe)\\s*", "")
                .replaceAll("^(In one sentence:?\\s*)", "")
                .replaceAll("^[\"']|[\"']$", "")
                .replaceAll("\\s+", " ")
                .trim();

        if (cleaned.length() > 200) cleaned = cleaned.substring(0, 197) + "...";
        return cleaned;
    }

    private double assessConfidence(String answer) {
        double conf = 0.55; // base confidence for model output
        int len = answer.length();
        if (len > 30 && len < 200) conf += 0.15;
        if (len > 80) conf += 0.05;

        String lower = answer.toLowerCase();
        if (lower.contains("should") || lower.contains("must")) conf -= 0.1;
        if (lower.contains("maybe") || lower.contains("perhaps") || lower.contains("might")) conf -= 0.1;

        return Math.max(0.2, Math.min(0.95, conf));
    }

    // ── Ollama API helpers ───────────────────────────────────────────

    private String extractResponseField(String json) {
        String searchKey = "\"response\":\"";
        int start = json.indexOf(searchKey);
        if (start < 0) {
            searchKey = "\"thinking\":\"";
            start = json.indexOf(searchKey);
            if (start < 0) return null;
        }
        start += searchKey.length();

        StringBuilder val = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n' -> { val.append(' '); i++; }
                    case 't' -> { val.append(' '); i++; }
                    case 'r' -> { val.append(' '); i++; }
                    case '"' -> { val.append('"'); i++; }
                    case '\\' -> { val.append('\\'); i++; }
                    default -> val.append(c);
                }
            } else if (c == '"') {
                break;
            } else {
                val.append(c);
            }
        }
        return val.toString().trim();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ── Records ──────────────────────────────────────────────────────

    public record BeliefEntry(String statement, double confidence, String source) {}

    private record ModelAnswer(String model, String statement, double confidence) {}
}
