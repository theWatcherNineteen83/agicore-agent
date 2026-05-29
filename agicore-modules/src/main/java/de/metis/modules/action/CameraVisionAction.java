package de.metis.modules.action;

import de.metis.kernel.action.ActionResult;
import de.metis.kernel.world.Belief;
import de.metis.kernel.world.WorldModel;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.logging.Logger;

/**
 * Vision understanding via minicpm-v:latest on Ollama.
 * <p>
 * Takes a camera snapshot (JPEG bytes), sends it to minicpm-v for description,
 * and stores the observation as a belief in the WorldModel.
 * <p>
 * Open Source: minicpm-v is Apache 2.0, runs locally on Ollama.
 * Zero external dependencies — uses only JDK HttpClient + Base64.
 * Metis can self-improve this class via CodeGenerationAction.
 */
public class CameraVisionAction {

    public static final String NAME = "camera-vision";
    private static final Logger LOG = Logger.getLogger(CameraVisionAction.class.getName());

    private static final String OLLAMA_URL = "http://192.168.22.204:11434";
    private static final String MODEL = "minicpm-v:latest";
    private static final String PROMPT = """
            Describe this image in German. One short, factual sentence.
            Mention what you see: objects, people, animals, vehicles, weather conditions.
            Be specific but concise. No speculation — only what is visible.
            """;

    private final String cameraName;
    private final String snapshotUrl;
    private final WorldModel worldModel;
    private final HttpClient http;

    public CameraVisionAction(String cameraName, String snapshotUrl, WorldModel worldModel) {
        this.cameraName = cameraName;
        this.snapshotUrl = snapshotUrl;
        this.worldModel = worldModel;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Execute the vision pipeline: fetch snapshot → describe → store belief.
     *
     * @return the description text, or null on failure
     */
    public String observe() {
        try {
            // 1. Fetch camera snapshot
            byte[] imageBytes = fetchSnapshot();
            if (imageBytes == null || imageBytes.length < 100) {
                LOG.fine("Camera " + cameraName + ": no image or too small");
                return null;
            }

            // 2. Send to minicpm-v for description
            String description = describeViaOllama(imageBytes);
            if (description == null || description.isBlank()) {
                LOG.fine("Camera " + cameraName + ": no description generated");
                return null;
            }

            // 3. Clean up description
            description = description.strip()
                    .replaceAll("^\"|\"$", "")
                    .replaceAll("^Das Bild zeigt |^Auf dem Bild ist |^In this image,? ", "");

            // 4. Store as belief with timestamp
            String belief = cameraName + ": " + description;
            double confidence = 0.75; // vision models are reasonably accurate
            worldModel.update(belief, confidence,
                    "camera-vision:" + cameraName, true);

            LOG.info("Camera " + cameraName + ": " + description);
            return description;

        } catch (Exception e) {
            LOG.warning("Camera vision failed for " + cameraName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Fetch JPEG snapshot from camera URL.
     */
    private byte[] fetchSnapshot() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(snapshotUrl))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) return null;
        return resp.body();
    }

    /**
     * Send image to minicpm-v via Ollama /api/chat with image support.
     */
    private String describeViaOllama(byte[] imageBytes) throws Exception {
        String b64 = Base64.getEncoder().encodeToString(imageBytes);

        String jsonBody = String.format("""
                {"model":"%s","messages":[
                  {"role":"user","content":"%s","images":["%s"]}
                ],"stream":false,
                 "options":{"temperature":0.1,"num_predict":80},
                 "keep_alive":0}
                """, MODEL, escapeJson(PROMPT), b64);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_URL + "/api/chat"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            LOG.warning("Ollama vision returned " + resp.statusCode());
            return null;
        }

        return extractJsonContent(resp.body());
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static String extractJsonContent(String json) {
        String search = "\"content\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();

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
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
