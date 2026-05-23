package de.metis.modules;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;

/**
 * Bootstraps Metis's WorldModel by querying a small Ollama model
 * for foundational knowledge. The answers become initial beliefs
 * with calibrated confidence scores.
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

    private final String ollamaUrl;
    private final String model;
    private final HttpClient http;

    public KnowledgeBootstrap(String ollamaBaseUrl, String model) {
        this.ollamaUrl = ollamaBaseUrl.endsWith("/") ? ollamaBaseUrl : ollamaBaseUrl;
        this.model = model;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Query the small model and return extracted belief statements.
     */
    public List<BeliefEntry> bootstrap() {
        List<BeliefEntry> beliefs = new ArrayList<>();
        LOG.info("Bootstrapping knowledge from model: " + model);

        for (String question : SEED_QUESTIONS) {
            try {
                String answer = askModel(question);
                if (answer != null && !answer.isBlank()) {
                    String statement = extractStatement(answer);
                    double confidence = assessConfidence(answer);
                    beliefs.add(new BeliefEntry(statement, confidence, "bootstrap:" + model));
                    LOG.fine("Bootstrap belief: " + statement + " (" + String.format("%.0f%%", confidence * 100) + ")");
                }
            } catch (Exception e) {
                LOG.fine("Bootstrap question failed: " + question + " — " + e.getMessage());
            }
        }

        LOG.info("Bootstrapped " + beliefs.size() + " beliefs from " + model);
        return beliefs;
    }

    private String askModel(String question) throws Exception {
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

    /** Clean up model output into a belief statement. */
    private String extractStatement(String raw) {
        // Remove common prefixes/qualifiers
        String cleaned = raw
                .replaceAll("^(Answer:|Response:|The answer is|I think|I believe)\\s*", "")
                .replaceAll("^(In one sentence:?\\s*)", "")
                .replaceAll("^[\"']|[\"']$", "")
                .replaceAll("\\s+", " ")
                .trim();

        // Truncate to reasonable length
        if (cleaned.length() > 200) cleaned = cleaned.substring(0, 197) + "...";

        return cleaned;
    }

    /** Heuristic confidence: coherent answers get higher confidence. */
    private double assessConfidence(String answer) {
        double conf = 0.6; // base confidence for model output

        // Longer, coherent answers = higher confidence (to a point)
        int len = answer.length();
        if (len > 30 && len < 200) conf += 0.15;
        if (len > 80) conf += 0.05;

        // Sentences with "should" or "must" = lower confidence (normative, not factual)
        String lower = answer.toLowerCase();
        if (lower.contains("should") || lower.contains("must")) conf -= 0.1;

        // Uncertainty markers = lower confidence
        if (lower.contains("maybe") || lower.contains("perhaps") || lower.contains("might")) conf -= 0.1;

        return Math.max(0.2, Math.min(0.95, conf));
    }

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

    public record BeliefEntry(String statement, double confidence, String source) {}
}
