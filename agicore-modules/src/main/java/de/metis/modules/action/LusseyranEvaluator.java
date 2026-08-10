package de.metis.modules.action;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Phase 13b — LusseyranEvaluator.
 * <p>
 * Interpretiert das JSON-Output des VoiceFeatureExtractors (Phase 13a)
 * durch einen LLM-Evaluator (nemotron-mini:4b) nach den Prinzipien von
 * Jacques Lusseyran ("Das wiedergefundene Licht", 1963).
 * <p>
 * Lusseyran, im Kindesalter erblindet, entwickelte eine außergewöhnliche
 * Fähigkeit: Er konnte Menschen allein anhand ihrer Stimme "sehen" —
 * ihre innere Haltung, Aufrichtigkeit, emotionale Verfassung und
 * Charakterzüge erfassen. Die Stimme wurde für ihn zu einem "inneren Licht".
 * <p>
 * Der Evaluator nutzt diesen Ansatz: Aus 25+ paralinguistischen Features
 * (Tonhöhe, Energie, Rhythmus, Timbre, Formanten, Stimmqualität) erzeugt
 * das LLM ein strukturiertes Sprecherprofil mit Lusseyran-Interpretation.
 * <p>
 * Output: JSON mit Lusseyran-Profil (Charaktereinschätzung,
 * emotionale Verfassung, Aufrichtigkeitseinschätzung, archetypische
 * Beschreibung) und einer narrativen Lusseyran-Vignette.
 * <p>
 * Modell: nemotron-mini:4b auf CPU-Instanz (Port 11438), ~1s/Analyse.
 *
 * @see VoiceFeatureAction Phase 13a Feature-Extraktion
 */
public class LusseyranEvaluator {

    private static final Logger LOG = Logger.getLogger(LusseyranEvaluator.class.getName());

    private static final String DEFAULT_OLLAMA_URL = "http://127.0.0.1:11438/api/chat";
    private static final String DEFAULT_MODEL = "nemotron-mini-agent";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient http;
    private final String ollamaUrl;
    private final String model;
    private final Duration timeout;

    public LusseyranEvaluator() {
        this(DEFAULT_OLLAMA_URL, DEFAULT_MODEL, DEFAULT_TIMEOUT);
    }

    public LusseyranEvaluator(String ollamaUrl, String model, Duration timeout) {
        this.ollamaUrl = ollamaUrl;
        this.model = model;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Evaluate a VoiceFeatureExtractor JSON output and return a Lusseyran speaker profile.
     *
     * @param featureJson the full JSON from voice_feature_extractor.py
     * @return structured Lusseyran profile as JSON string, or error JSON
     */
    public String evaluate(String featureJson) {
        if (featureJson == null || featureJson.isBlank()) {
            return "{\"error\":\"empty feature JSON\",\"lusseyran_profile\":null}";
        }

        String prompt = buildLusseyranPrompt(featureJson);
        String response = callModel(prompt);

        if (response == null) {
            return """
                    {"error":"LLM call failed",
                     "lusseyran_profile":{"stimmcharakter":"unbekannt",
                     "emotionale_verfassung":"nicht analysierbar",
                     "aufrichtigkeit":"unklar",
                     "archetyp":"unbekannt",
                     "vignette":"Die Stimmanalyse konnte nicht abgeschlossen werden."}}""";
        }

        return extractJsonFromResponse(response);
    }

    // ── Prompt Construction ──────────────────────────────────────

    /**
     * Build the Lusseyran evaluation prompt with system + user messages.
     * <p>
     * System prompt: Lusseyran's principles for voice-based person analysis.
     * User prompt: The extracted features in structured form.
     */
    private String buildLusseyranPrompt(String featureJson) {
        // Sanitize the feature JSON for embedding in a JSON string
        String safeFeatures = escapeJson(featureJson);

        return String.format("""
                {
                  "model": "%s",
                  "messages": [
                    {
                      "role": "system",
                      "content": "Du bist ein Stimmanalyst nach den Prinzipien von Jacques Lusseyran "
                        + "(1924–1971). Lusseyran, im Alter von 7 Jahren erblindet, entwickelte die "
                        + "Fähigkeit, Menschen allein anhand ihrer Stimme zu 'sehen'. Für ihn wurde "
                        + "die Stimme zu einem 'inneren Licht', das den Charakter, die emotionale "
                        + "Verfassung und die Aufrichtigkeit eines Menschen offenbarte.\\n\\n"
                        + "Deine Aufgabe: Analysiere die gegebenen paralinguistischen Features "
                        + "(Tonhöhe, Energie, Rhythmus, Timbre, Formanten, Stimmqualität) und "
                        + "erstelle ein Sprecherprofil nach Lusseyran-Art.\\n\\n"
                        + "INTERPRETATIONSHILFE (Lusseyran-Prinzipien):\\n"
                        + "- TIEFE Stimme mit VARIABILITÄT → gefestigte Persönlichkeit, innere Ruhe\\n"
                        + "- HOHE Stimme mit MONOTONIE → angespannt, kontrolliert, möglicherweise maskiert\\n"
                        + "- DYNAMISCHE Energie → lebendig, authentisch, emotional präsent\\n"
                        + "- MONOTONE Energie → distanziert, erschöpft oder strategisch kontrolliert\\n"
                        + "- SCHNELLES Tempo mit VIELEN PAUSEN → denkend-sprechend, reflektiert\\n"
                        + "- LANGSAMES Tempo mit WENIGEN PAUSEN → bedächtig, autoritativ\\n"
                        + "- HOHE STIMMHAFTIGKEIT → klare Präsenz, 'volle' Stimme = geerdet\\n"
                        + "- NIEDRIGE STIMMHAFTIGKEIT (behaucht) → verletzlich, intim oder unsicher\\n"
                        + "- HOHER JITTER/SHIMMER → emotionale Beteiligung oder Anspannung\\n"
                        + "- WEITE FORMAT-DISPERSION → entspannte Artikulation, 'offene' Stimme\\n\\n"
                        + "WICHTIG: Lusseyran analysierte OHNE visuelle Ablenkung. "
                        + "Fokussiere dich ausschließlich auf das, was die Stimme verrät. "
                        + "Vermeide Spekulationen über Aussehen, Alter (außer aus Stimme ableitbar) "
                        + "oder sozialen Status.\\n\\n"
                        + "Antworte NUR mit diesem JSON-Format (kein Markdown, kein Extra-Text):\\n"
                        + "{\\n"
                        + "  \\\"stimmcharakter\\\": \\\"<2-3 Adjektive, z.B. warm-tief-lebendig>\\\",\\n"
                        + "  \\\"emotionale_verfassung\\\": \\\"<1 Satz zur emotionalen Tönung>\\\",\\n"
                        + "  \\\"aufrichtigkeit\\\": \\\"<hoch|mittel|niedrig|unklar>\\\",\\n"
                        + "  \\\"aufrichtigkeit_begruendung\\\": \\\"<1 Satz: warum diese Einschätzung>\\\",\\n"
                        + "  \\\"archetyp\\\": \\\"<z.B. Der ruhige Weise, Die energische Erzählerin, Der bedächtige Diplomat>\\\",\\n"
                        + "  \\\"vignette\\\": \\\"<2-3 Sätze im Stil von Lusseyran: poetische, präzise Beschreibung der Stimme als 'inneres Licht'>\\\"\\n"
                        + "}"
                    },
                    {
                      "role": "user",
                      "content": "Analysiere folgende Stimmfeatures:\\n\\n%s"
                    }
                  ],
                  "stream": false,
                  "options": {
                    "temperature": 0.3,
                    "top_p": 0.9,
                    "num_predict": 400,
                    "num_ctx": 4096
                  },
                  "keep_alive": "30m"
                }
                """, model, safeFeatures);
    }

    // ── Ollama Call ──────────────────────────────────────────────

    private String callModel(String jsonBody) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.warning("LusseyranEvaluator: Ollama HTTP " + response.statusCode());
                return null;
            }

            // Extract "message"."content" from /api/chat response
            return extractContentField(response.body());
        } catch (Exception e) {
            LOG.fine(() -> "LusseyranEvaluator call failed: " + e.getMessage());
            return null;
        }
    }

    // ── Response Parsing ─────────────────────────────────────────

    /**
     * Extract "message"."content" from an Ollama /api/chat response.
     * Falls back to extracting a JSON-looking substring from the raw body.
     */
    private String extractContentField(String json) {
        // Try "content" field (nested under "message")
        String content = extractJsonString(json, "content");
        if (content != null && !content.isBlank()) {
            return content;
        }
        // Try "response" field (/api/generate format)
        content = extractJsonString(json, "response");
        if (content != null && !content.isBlank()) {
            return content;
        }
        // Last resort: raw body
        LOG.fine(() -> "LusseyranEvaluator: no content/response field in: "
                + json.substring(0, Math.min(200, json.length())));
        return json;
    }

    /**
     * Extract just the JSON object from the LLM response.
     * Handles markdown fences, extra text before/after JSON.
     */
    private String extractJsonFromResponse(String response) {
        if (response == null || response.isBlank()) return null;

        // Strip markdown code fences
        String clean = response;
        int fenceStart = clean.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = clean.indexOf('\n', fenceStart) + 1;
            int fenceEnd = clean.indexOf("```", contentStart);
            if (fenceEnd > contentStart) {
                clean = clean.substring(contentStart, fenceEnd).strip();
            }
        }

        // Find outermost JSON object
        int braceStart = clean.indexOf('{');
        int braceEnd = clean.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            // Validate basic JSON structure (matching braces)
            String candidate = clean.substring(braceStart, braceEnd + 1);
            if (countChar(candidate, '{') == countChar(candidate, '}')) {
                return candidate;
            }
        }

        // Fallback: return raw response wrapped
        return "{\"raw_response\":" + escapeJson(clean) + "}";
    }

    // ── JSON Helpers ─────────────────────────────────────────────

    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) {
            search = "\"" + key + "\": \"";
            start = json.indexOf(search);
        }
        if (start < 0) return null;
        start += search.length();

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    case 'r' -> { sb.append('\r'); i++; }
                    case '"' -> { sb.append('"'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    default -> { sb.append(c); }
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return !sb.isEmpty() ? sb.toString() : null;
    }

    @SuppressWarnings("unused")
    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }

    // ── Accessors ────────────────────────────────────────────────

    public String model() { return model; }
    public String ollamaUrl() { return ollamaUrl; }
}
