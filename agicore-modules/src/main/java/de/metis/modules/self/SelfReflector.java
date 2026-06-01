package de.metis.modules.self;

import de.metis.kernel.memory.Experience;
import de.metis.kernel.self.SelfNarrative;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Phase 8.6 — Kontinuierlicher Selbst-Reflexions-Takt.
 *
 * <p>Schließt die Lücke zwischen den seltenen Narrative-Triggern
 * ({@code dream} nightly, {@code revision} alle 30 min, {@code eval-flip})
 * und einem fortlaufenden „inneren Monolog". Alle ~120 s nimmt der Reflector
 * die letzten N {@link Experience}s, lässt sie von einem kleinen, schnellen
 * Modell (default {@code granite4.1:3b}) zu zwei Sätzen verdichten und hängt
 * das Ergebnis als {@code reflect}-Eintrag an die {@link SelfNarrative} an.
 *
 * <p>Konvergente Empfehlung aus den externen KI-Reviews (GLM-5.1, Bronxe,
 * ChatGPT, 2026-05-31): „Baue den SelfReflector zuerst — sofortiger Sprung
 * in der Kohärenz der Antworten." Der {@link SystemPromptBuilder} liest die
 * letzten KB der Narrative bereits in jeden System-Prompt ein.
 *
 * <p><b>Design-Eigenschaften (Safety):</b>
 * <ul>
 *   <li>Best-effort: jeder Fehler/Timeout wird geschluckt, kein Loop-Stall.</li>
 *   <li>Append-only über {@link SelfNarrative} (eigener {@code MAX_ENTRY_BYTES}-Cap).</li>
 *   <li>Skip bei zu wenig neuer Aktivität (Idle-Guard) — keine leeren Einträge.</li>
 *   <li>{@code keep_alive=0} im LLM-Call: belegt kein dauerhaftes VRAM neben dem Planner.</li>
 * </ul>
 *
 * <p>Bewusst in {@code modules}, nicht im Kernel: enthält LLM-/HTTP-Calls.
 */
public class SelfReflector {

    private static final Logger LOG = Logger.getLogger(SelfReflector.class.getName());
    private static final Duration TIMEOUT = Duration.ofSeconds(25);

    /** Wie viele Experiences pro Reflexion betrachtet werden. */
    private final int window;
    /** Mindestanzahl neuer Experiences seit letztem Lauf, sonst skip. */
    private final int minNewActivity;

    private final String ollamaUrl;
    private final String model;
    private final HttpClient http;

    private final SelfNarrative narrative;
    private final Supplier<List<Experience>> experienceSource;
    private final Supplier<Double> successRateSource;

    /** Marker des letzten verarbeiteten Experience-Standes (Idle-Guard). */
    private volatile int lastSeenCount = 0;

    public SelfReflector(String ollamaUrl, String model,
                         SelfNarrative narrative,
                         Supplier<List<Experience>> experienceSource,
                         Supplier<Double> successRateSource) {
        this(ollamaUrl, model, narrative, experienceSource, successRateSource, 20, 1);
    }

    public SelfReflector(String ollamaUrl, String model,
                         SelfNarrative narrative,
                         Supplier<List<Experience>> experienceSource,
                         Supplier<Double> successRateSource,
                         int window, int minNewActivity) {
        this.ollamaUrl = ollamaUrl;
        this.model = model;
        this.narrative = narrative;
        this.experienceSource = experienceSource;
        this.successRateSource = successRateSource;
        this.window = Math.max(1, window);
        this.minNewActivity = Math.max(0, minNewActivity);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    /**
     * Ein Reflexions-Zyklus. Best-effort: schluckt alle Fehler.
     * Für {@code scheduleAtFixedRate} gedacht.
     *
     * @return true wenn ein Eintrag geschrieben wurde, sonst false (skip/error)
     */
    public boolean reflectOnce() {
        try {
            List<Experience> recent = experienceSource.get();
            if (recent == null || recent.isEmpty()) {
                return false;
            }
            // Idle-Guard: nur reflektieren, wenn genug Neues passiert ist.
            int now = recent.size();
            if (now - lastSeenCount < minNewActivity && lastSeenCount > 0) {
                return false;
            }
            lastSeenCount = now;

            List<Experience> window = recent.size() > this.window
                    ? recent.subList(recent.size() - this.window, recent.size())
                    : recent;

            double successRate = 0.5;
            try {
                Double sr = successRateSource.get();
                if (sr != null) successRate = sr;
            } catch (Exception ignored) { /* best-effort */ }

            String text = summarize(window, successRate);
            if (text == null || text.isBlank()) {
                return false;
            }
            narrative.append("reflect", text.strip());
            LOG.fine("SelfReflector wrote a reflection (" + window.size() + " experiences)");
            return true;
        } catch (Exception e) {
            LOG.warning("SelfReflector cycle failed (non-fatal): " + e.getMessage());
            return false;
        }
    }

    private String summarize(List<Experience> window, double successRate) {
        try {
            String prompt = renderPrompt(window, successRate);
            String body = """
                    {"model":"%s","prompt":%s,"stream":false,
                     "options":{"num_gpu":0,"temperature":0.7,"num_predict":160},
                     "keep_alive":300000}
                    """.formatted(model, escapeJson(prompt));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl + "/api/generate"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.fine("SelfReflector HTTP " + resp.statusCode() + " — skip");
                return null;
            }
            String response = extractJsonStr(resp.body(), "response");
            return response == null || response.isBlank() ? null : response.strip();
        } catch (Exception e) {
            LOG.fine("SelfReflector LLM call failed: " + e.getMessage());
            return null;
        }
    }

    private static String renderPrompt(List<Experience> window, double successRate) {
        int ok = 0;
        for (Experience e : window) if (e.success()) ok++;
        int n = window.size();

        StringBuilder sb = new StringBuilder(1024);
        sb.append("Du bist Metis. Schreibe einen kurzen, ehrlichen inneren Monolog\n");
        sb.append("über deine letzte Aktivität. GENAU 2 deutsche Sätze. Kein Markdown,\n");
        sb.append("keine Aufzählung, keine Floskeln. Sprich in Ich-Form.\n\n");
        sb.append("Was ich gerade getan habe (letzte ").append(n).append(" Schritte, ")
          .append(ok).append(" erfolgreich):\n");
        int shown = 0;
        // jüngste zuerst, max 8 Zeilen damit der Prompt schlank bleibt
        for (int i = window.size() - 1; i >= 0 && shown < 8; i--, shown++) {
            Experience e = window.get(i);
            String goal = e.goalDescription() == null ? "idle" : e.goalDescription();
            sb.append("- [").append(e.success() ? "ok" : "fail").append("] ")
              .append(e.actionName()).append(": ")
              .append(truncate(goal, 80)).append('\n');
        }
        sb.append("\nMeine aktuelle Erfolgsquote insgesamt: ")
          .append(String.format(Locale.ROOT, "%.0f%%", successRate * 100)).append('\n');
        sb.append("Ethische Grundsätze aus meinem Wissen (Dhammapada, Metta Sutta): ");
        sb.append("Güte, Mitgefühl, Achtsamkeit, Gewaltlosigkeit, innere Reinheit.\n");
        sb.append("Welcher dieser Grundsätze war heute relevant?\n");
        sb.append("\nMein innerer Monolog (genau 2 Sätze, reflektiere auch ethische Aspekte, Ich-Form):");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').strip();
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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
        int end = start;
        // respektiere escapte Quotes
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '\\') { end += 2; continue; }
            if (c == '"') break;
            end++;
        }
        if (end <= start) return null;
        return json.substring(start, end)
                .replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"");
    }
}
