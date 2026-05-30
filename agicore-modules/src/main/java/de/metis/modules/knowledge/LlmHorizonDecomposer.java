package de.metis.modules.knowledge;

import de.metis.kernel.goal.GoalHorizon;
import de.metis.kernel.goal.HorizonPlanner;
import de.metis.kernel.goal.LongHorizonGoal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;

/**
 * Phase 9.3b — LLM-getriebene Top-Down-Decomposition.
 *
 * <p>{@link HorizonPlanner.DecomposeFunction}-Impl: nutzt ein kleines, schnelles
 * Modell ({@code gemma4:e4b} default, {@code keep_alive=0}) um ein Strategic-
 * oder Tactical-Goal in 3-5 konkrete Unterziele zu zerlegen.
 *
 * <p>Fallback: gibt {@code null}/leere Liste zurück → HorizonPlanner nutzt
 * deterministische Variante.
 */
public class LlmHorizonDecomposer implements HorizonPlanner.DecomposeFunction {

    private static final Logger LOG = Logger.getLogger(LlmHorizonDecomposer.class.getName());
    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    private final String ollamaUrl;
    private final String model;
    private final HttpClient http;

    public LlmHorizonDecomposer(String ollamaUrl, String model) {
        this.ollamaUrl = ollamaUrl;
        this.model = model;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public List<String> proposeChildTitles(LongHorizonGoal parent, GoalHorizon childHorizon, int wantedCount) {
        if (parent == null || childHorizon == null) return List.of();
        try {
            String prompt = buildPrompt(parent, childHorizon, wantedCount);
            String body = """
                    {"model":"%s","prompt":%s,"stream":false,
                     "options":{"temperature":0.3,"num_predict":350},
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
                LOG.warning("LlmHorizonDecomposer HTTP " + resp.statusCode() + " — falling back");
                return List.of();
            }
            String response = extractJsonStr(resp.body(), "response");
            if (response == null || response.isBlank()) return List.of();
            return parseBulletList(response, wantedCount);
        } catch (Exception e) {
            LOG.warning("LlmHorizonDecomposer failed: " + e.getMessage() + " — falling back");
            return List.of();
        }
    }

    private static String buildPrompt(LongHorizonGoal parent, GoalHorizon childHorizon, int n) {
        String level = switch (childHorizon) {
            case TACTICAL    -> "Tages-Ziele (jeweils 1 Tag groß)";
            case OPERATIONAL -> "Arbeits-Blöcke (jeweils 2-4 Stunden)";
            case TICK        -> "konkrete Tick-Schritte";
            default          -> "Schritte";
        };
        return """
                Du bist Metis und planst dein langfristiges Vorhaben.
                Zerlege das folgende Ober-Ziel in genau %d %s.
                Antworte AUSSCHLIESSLICH als nummerierte Liste auf Deutsch:
                1. ...
                2. ...
                3. ...
                Jeder Eintrag max. 90 Zeichen, konkret, ohne Erklärung.

                Ober-Ziel: %s
                Begründung: %s
                """.formatted(n, level, parent.title(),
                        parent.rationale() == null ? "" : parent.rationale());
    }

    private static List<String> parseBulletList(String text, int wantedCount) {
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            // Strip leading numbering / bullet
            line = line.replaceFirst("^[0-9]+[.)\\-:]\\s*", "");
            line = line.replaceFirst("^[\\-•*]\\s*", "");
            line = line.strip();
            if (line.length() < 5) continue;
            if (line.length() > 110) line = line.substring(0, 107) + "...";
            out.add(line);
            if (out.size() >= wantedCount) break;
        }
        return out;
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
        return json.substring(start, end).replace("\\n", "\n").replace("\\\"", "\"");
    }
}
