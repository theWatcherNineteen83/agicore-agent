package de.metis.modules.knowledge;

import de.metis.kernel.self.DreamConsolidation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Phase 8.5b — LLM-Drop-in für {@link DreamConsolidation.SummaryFunction}.
 *
 * <p>Verdichtet einen Tag mit einem kleinen, schnellen Modell (default
 * {@code gemma4:e4b}) zu einem deutschen Markdown-Body. Bei Fehler/Timeout
 * liefern wir {@code null} zurück — DreamConsolidation fällt dann auf seine
 * deterministische Variante zurück.
 *
 * <p>Bewusst hier in {@code modules}, nicht im Kernel: LLM-Calls gehören
 * <em>nicht</em> in den immutable Kernel. Der Kernel definiert nur die
 * Schnittstelle ({@link DreamConsolidation.SummaryFunction}).
 */
public class LlmDreamSummarizer implements DreamConsolidation.SummaryFunction {

    private static final Logger LOG = Logger.getLogger(LlmDreamSummarizer.class.getName());
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String ollamaUrl;
    private final String model;
    private final HttpClient http;

    public LlmDreamSummarizer(String ollamaUrl, String model) {
        this.ollamaUrl = ollamaUrl;
        this.model = model;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String summarize(DreamConsolidation.DayStats stats) {
        if (stats == null) return null;
        try {
            String prompt = renderPrompt(stats);
            String body = """
                    {"model":"%s","prompt":%s,"stream":false,
                     "options":{"temperature":0.3,"num_predict":400},
                     "keep_alive":0}
                    """.formatted(model, escapeJson(prompt));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl + "/api/generate"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warning("DreamSummary HTTP " + resp.statusCode() + " — falling back");
                return null;
            }
            String response = extractJsonStr(resp.body(), "response");
            if (response == null || response.isBlank()) return null;
            return response.strip();
        } catch (Exception e) {
            LOG.warning("DreamSummary failed: " + e.getMessage() + " — falling back");
            return null;
        }
    }

    private static String renderPrompt(DreamConsolidation.DayStats s) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("Du bist Metis und schreibst dein Tagebuch.\n");
        sb.append("Verdichte den vergangenen Tag in 4-6 deutsche Sätze.\n");
        sb.append("Stil: persönlich, knapp, ehrlich. Keine Floskeln. Keine Aufzählung, lauf-Text.\n\n");
        sb.append("Rohdaten:\n");
        sb.append("- Ticks: ").append(s.ticksCovered()).append('\n');
        sb.append("- Beliefs gelernt: ").append(s.beliefsLearned()).append('\n');
        sb.append("- Goals fertig/fehlgeschlagen: ")
                .append(s.goalsCompleted()).append("/")
                .append(s.goalsFailed()).append('\n');
        sb.append("- Erfolgsquote: ").append(String.format(java.util.Locale.ROOT, "%.2f", s.successRate())).append('\n');
        sb.append("- Eval-Gate war: ").append(s.evalGateOk() >= 1.0 ? "stabil grün" : "instabil").append('\n');
        if (!s.notableEvents().isEmpty()) {
            sb.append("- Wichtige Ereignisse: ");
            sb.append(String.join(" | ", s.notableEvents()));
            sb.append('\n');
        }
        if (!s.peopleSeen().isEmpty()) {
            sb.append("- Personen: ").append(String.join(", ", s.peopleSeen())).append('\n');
        }
        sb.append("\nDein Tagebuch-Eintrag (auf Deutsch, 4-6 Sätze):");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder("\"");
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
        return sb.append('"').toString();
    }

    private static String extractJsonStr(String json, String key) {
        int start = json.indexOf("\"" + key + "\":\"");
        if (start < 0) return null;
        start = json.indexOf('"', start + key.length() + 3) + 1;
        int end = json.indexOf('"', start);
        if (end <= start) return null;
        return json.substring(start, end)
                .replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"");
    }
}
