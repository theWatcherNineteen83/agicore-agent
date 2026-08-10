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
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

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
                     "keep_alive":"30m"}
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
            case TACTICAL    -> "Tagesziele (jeweils ~1 Tag Aufwand)";
            case OPERATIONAL -> "Arbeitspakete (jeweils ~2-4 Stunden)";
            case TICK        -> "konkrete Einzelschritte (jeweils ~30-60 Minuten)";
            default          -> "Schritte";
        };
        return """
                Du bist Metis, eine selbst-evolvierende Java-AGI. Du planst ein konkretes
                Software-Vorhaben und zerlegst es in umsetzbare Einzelschritte.

                WICHTIG: KEINE generischen Platzhalter-Titel wie "Tag-Schritt 1",
                "Block 2" oder "Tick 3". Jeder Titel MUSS den konkreten Inhalt
                beschreiben — was genau wird getan? Beispiel: stattdessen
                "Tag-Schritt 1: X" schreibe "InitiativeLevel Enum mit 5 Stufen implementieren".
                Antworte NUR auf Deutsch. KEIN Englisch. KEIN YAML. KEINE Code-Blöcke.
                KEINE Einleitung, keine Zusammenfassung, keine Erklärung.

                Zerlege das folgende Ober-Ziel in genau %d %s.
                Antworte NUR als nummerierte Liste, ein konkreter Titel pro Zeile:
                1. Konkrete Aktion mit Verb...
                2. Konkrete Aktion mit Verb...
                3. Konkrete Aktion mit Verb...
                Jeder Titel: max. 100 Zeichen, beginnt mit einem VERB, NIE mit "Tag-" oder "Block".

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
            // Skip code fences, YAML/JSON fragments, English preambles
            if (line.startsWith("```") || line.startsWith("~~~")) continue;
            if (line.startsWith("#")) continue;
            if (line.startsWith("[") && (line.endsWith("]") || line.contains("postcondition"))) continue;
            if (line.startsWith("child_") || line.startsWith("beliefs_")) continue;
            // Skip English-only long preambles and YAML key:value lines
            if (line.contains(": ") && line.length() < 80 && !line.matches("^[0-9a-zA-Z][.)].*")) {
                if (line.matches("^[a-zA-Z_]+:.*") && !line.matches("(?i)^(erstelle|implementiere|definiere|baue|schreibe|entwickle|konfiguriere|teste|integriere|analysiere|dokumentiere|plane|recherchiere|optimier|migriere|validiere|erforsche|erweitere|passe|aktualisiere|erzeuge|erstelle|setze|richte).*")) continue;
            }
            // Strip leading numbering / bullet (also a. b. c.)
            line = line.replaceFirst("^[0-9]+[.)\\-:]\\s*", "");
            line = line.replaceFirst("^[a-zA-Z][.)\\-:]\\s*", "");
            line = line.replaceFirst("^[\\-•*]\\s*", "");
            line = line.strip();
            // Reject generic placeholder patterns
            if (line.matches("(?i).*(tag-schritt|block\\s+[0-9]|tick\\s+[0-9]).*")) continue;
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
