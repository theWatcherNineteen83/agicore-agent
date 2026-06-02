package de.metis.modules.events;

import de.metis.kernel.world.Belief;
import de.metis.modules.Agent;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Polls public webcam images and analyzes them via Ollama Vision.
 * <p>
 * Currently configured for Coburg Marktplatz (bergfex webcam ID 14275).
 * Downloads JPEG snapshot → sends to Ollama vision model → generates
 * Beliefs (weather, activity, crowd) and Goals for notable events.
 * <p>
 * Category: read (only retrieves and analyzes public data).
 */
public class WebcamPollingTrigger implements EventTrigger {

    private static final Logger LOG = Logger.getLogger(WebcamPollingTrigger.class.getName());

    // ── Webcam configuration ──────────────────────────────────
    private final List<WebcamConfig> webcams = new ArrayList<>();

    // ── Ollama vision configuration ───────────────────────────
    private static final String OLLAMA_URL = "http://192.168.22.204:11434/api/chat";
    private static final String VISION_MODEL = "minicpm-v:latest";  // lightweight vision model
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMinutes(5);

    // Kein shared HttpClient mehr — jeder Request bekommt einen frischen Client
    // (vermeidet "selector manager closed" bei Dauer-Polling)
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollingThread;

    // State tracking
    private final Map<String, String> lastDescriptions = new ConcurrentHashMap<>();
    private Instant lastPoll = null;
    private int analysisCount = 0;

    /**
     * @param webcamUrls list of webcam image URLs to monitor
     */
    public WebcamPollingTrigger() {
        // ── Coburg Live-Webcams ──────────────────────────────────

        // Coburg Marktplatz — bergfex
        webcams.add(new WebcamConfig(
                "Coburg-Marktplatz",
                "https://images.bergfex.at/webcams/?id=14275&2&format=44",
                "Historischer Marktplatz Coburg mit Rathaus, Stadthaus und Markttreiben"
        ));

        // Coburg Marktplatz — feratel (Stadt Coburg, Blick auf Stadthaus)
        webcams.add(new WebcamConfig(
                "Coburg-Stadthaus",
                "https://www.feratel.com/webcams/?id=02d4e2aa-2c1c-4f0b-a7d1-0a5e3b8f9c1d&design=live&cam=1",
                "Blick vom Stadthaus auf den Coburger Marktplatz"
        ));

        // Coburg — Veste von der Morizkirche (Coburg Marketing)
        webcams.add(new WebcamConfig(
                "Coburg-Veste-Panorama",
                "https://www.coburgmarketing.de/informieren/webcam",
                "Panoramablick von der Morizkirche über Coburg mit Veste"
        ));

        // Flugplatz Coburg Brandensteinsebene (EDQC)
        webcams.add(new WebcamConfig(
                "Coburg-Flugplatz",
                "https://www.coburg.de/coburg-erleben/webcams/webcams/webcam-flugplatz-steinruecken.php",
                "Flugplatz Coburg Brandensteinsebene — Landebahn und Flugbetrieb"
        ));

        // Albertsplatz Coburg
        webcams.add(new WebcamConfig(
                "Coburg-Albertsplatz",
                "https://www.coburg.de/coburg-erleben/webcams/webcams/webcam-blick-auf-albertsplatz.php",
                "Albertsplatz Coburg — Geschäfte und Fußgängerzone"
        ));
    }

    @Override
    public String name() { return "webcam-polling"; }

    @Override
    public String description() {
        return "Polls " + webcams.size() + " webcam(s) every "
                + POLL_INTERVAL.toMinutes() + "min, analyzes via Ollama Vision";
    }

    @Override
    public void start(Agent agent) {
        if (running.getAndSet(true)) return;

        pollingThread = Thread.ofVirtual().name("webcam-poll").start(() -> {
            while (running.get()) {
                try {
                    pollAndAnalyze(agent);
                    Thread.sleep(POLL_INTERVAL.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        LOG.info("Webcam polling started — " + webcams.size()
                + " camera(s) every " + POLL_INTERVAL.toMinutes() + "min");
    }

    @Override
    public void stop() {
        running.set(false);
        if (pollingThread != null) pollingThread.interrupt();
        LOG.info("Webcam polling stopped after " + analysisCount + " analyses");
    }

    // ── Poll & Analyze ────────────────────────────────────────────

    private void pollAndAnalyze(Agent agent) {
        for (WebcamConfig cam : webcams) {
            try {
                // 1. Download image
                byte[] imageBytes = downloadImage(cam.url);
                if (imageBytes == null) continue;

                // 2. Send to Ollama Vision
                String description = analyzeWithVision(cam, imageBytes);
                if (description == null || description.isBlank()) continue;

                analysisCount++;
                LOG.fine("Webcam " + cam.name + ": " + description);

                // 3. Create belief
                String beliefText = cam.name + ": " + description;
                agent.worldModel().update(beliefText, 0.8, "WEBCAM_VISION", true);

                // 4. Detect changes vs last poll
                String last = lastDescriptions.put(cam.name, description);
                boolean changed = last != null && !last.equals(description);

                // 5. Generate goals for notable observations
                String lower = description.toLowerCase();
                int priority = 30;
                double reward = 0.4;

                if (lower.contains("markt") || lower.contains("stand") || lower.contains("menschen")) {
                    priority = 50;
                    reward = 0.6;
                }
                if (lower.contains("regen") || lower.contains("schnee") || lower.contains("sturm")) {
                    priority = 70;
                    reward = 0.75;
                }
                if (lower.contains("sonne") || lower.contains("sonnig") || lower.contains("blauer himmel")) {
                    priority = 40;
                    reward = 0.5;
                }
                if (lower.contains("viele") && lower.contains("menschen")) {
                    priority = 60;
                    reward = 0.65;
                }

                String goal = cam.name + ": " + description;
                if (changed) {
                    goal += " (verändert seit letztem Poll)";
                    priority = Math.min(priority + 15, 90);
                }

                agent.addGoal(goal, "webcam", priority, reward, 3);

            } catch (Exception e) {
                LOG.warning("Webcam " + cam.name + " error: " + e.getMessage());
            }
        }

        lastPoll = Instant.now();
    }

    // ── Image Download ────────────────────────────────────────────

    private byte[] downloadImage(String url) {
        try {
            // Frischer HttpClient pro Request — verhindert "selector manager closed"
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<byte[]> resp = client.send(req,
                    HttpResponse.BodyHandlers.ofByteArray());

            if (resp.statusCode() != 200 || resp.body().length < 100) {
                LOG.warning("Webcam image download failed: HTTP " + resp.statusCode()
                        + ", " + (resp.body() != null ? resp.body().length : 0) + " bytes");
                return null;
            }

            return resp.body();
        } catch (Exception e) {
            LOG.warning("Webcam download error: " + e.getMessage());
            return null;
        }
    }

    // ── Ollama Vision Analysis ─────────────────────────────────────

    private String analyzeWithVision(WebcamConfig cam, byte[] imageBytes) {
        try {
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);

            String prompt = "Beschreibe kurz in 1-2 deutschen Sätzen was auf diesem Bild zu sehen ist. "
                    + "Fokus auf: Wetter, wie viele Menschen, besondere Aktivitäten. "
                    + "Das Bild zeigt: " + cam.description + ".";

            String jsonBody = String.format("""
                    {
                      "model": "%s",
                      "stream": false,
                      "messages": [
                        {
                          "role": "user",
                          "content": "%s",
                          "images": ["%s"]
                        }
                      ],
                      "options": {
                        "temperature": 0.2,
                        "num_predict": 100
                      },
                      "keep_alive": 0
                    }
                    """, VISION_MODEL, escapeJson(prompt), base64Image);

            // Frischer HttpClient pro Request — verhindert "selector manager closed"
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_URL))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.warning("Ollama Vision returned " + response.statusCode());
                return null;
            }

            // Extract "content" from /api/chat response
            return extractChatContent(response.body());

        } catch (Exception e) {
            LOG.warning("Vision analysis error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Extract the "content" field from Ollama /api/chat JSON response.
     */
    private String extractChatContent(String json) {
        int contentIdx = json.indexOf("\"content\":\"");
        if (contentIdx < 0) return null;

        int start = contentIdx + "\"content\":\"".length();
        StringBuilder val = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n' -> { val.append('\n'); i++; }
                    case 't' -> { val.append('\t'); i++; }
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
        return val.toString();
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
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
        return sb.toString();
    }

    // ── Accessors ──────────────────────────────────────────────────

    public int analysisCount() { return analysisCount; }
    public Instant lastPollTime() { return lastPoll; }
    public Map<String, String> lastDescriptions() {
        return Map.copyOf(lastDescriptions);
    }

    // ── Config Record ──────────────────────────────────────────────

    record WebcamConfig(String name, String url, String description) {}
}
