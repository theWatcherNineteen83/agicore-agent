package de.metis.modules.action;

import de.metis.kernel.action.ActionResult;
import de.metis.kernel.world.Belief;
import de.metis.kernel.world.WorldModel;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

            // 2. Persist snapshot to disk BEFORE LLM call - multi-modal memory
            //    (Metis can later "show me what you saw at 14:23" instead of only
            //    relying on the textual belief.)
            SnapshotRef ref = persistSnapshot(imageBytes);

            // 3. Send to minicpm-v for description
            String description = describeViaOllama(imageBytes);
            if (description == null || description.isBlank()) {
                LOG.fine("Camera " + cameraName + ": no description generated");
                return null;
            }

            // 4. Clean up description
            description = description.strip()
                    .replaceAll("^\"|\"$", "")
                    .replaceAll("^Das Bild zeigt |^Auf dem Bild ist |^In this image,? ", "");

            // 5. Store belief with snapshot reference (sha256 prefix + path)
            String belief = ref != null
                    ? cameraName + ": " + description + " [img=" + ref.sha256().substring(0, 12)
                            + " path=" + ref.path() + "]"
                    : cameraName + ": " + description;
            double confidence = 0.75;
            worldModel.update(belief, confidence,
                    "camera-vision:" + cameraName, true);

            LOG.info("Camera " + cameraName + ": " + description
                    + (ref != null ? " (saved: " + ref.path() + ")" : ""));
            return description;

        } catch (Exception e) {
            LOG.warning("Camera vision failed for " + cameraName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Persist a JPEG snapshot to data/snapshots/{camera}/YYYY-MM-DD/HH-MM-SS-{sha8}.jpg.
     * Pruning is the operator responsibility (logrotate / tmpwatch).
     * Returns null on IO failure (vision still runs).
     */
    private SnapshotRef persistSnapshot(byte[] imageBytes) {
        try {
            String sha = sha256(imageBytes);
            Instant now = Instant.now();
            ZoneId tz = ZoneId.systemDefault();
            String day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(tz).format(now);
            String hms = DateTimeFormatter.ofPattern("HH-mm-ss").withZone(tz).format(now);
            Path dir = Paths.get(SNAPSHOT_ROOT, cameraName, day);
            Files.createDirectories(dir);
            String fileName = hms + "-" + sha.substring(0, 8) + ".jpg";
            Path file = dir.resolve(fileName);
            if (!Files.exists(file)) {
                Files.write(file, imageBytes);
            }
            return new SnapshotRef(file.toString(), sha);
        } catch (Exception e) {
            LOG.warning("Snapshot persist failed (" + cameraName + "): " + e.getMessage());
            return null;
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "nohash";
        }
    }

    /** Snapshot file reference stored inline in beliefs for later retrieval. */
    public record SnapshotRef(String path, String sha256) {}

    /** Override via -Dmetis.snapshot.root=/var/lib/metis/snapshots if needed. */
    private static final String SNAPSHOT_ROOT =
            System.getProperty("metis.snapshot.root", "data/snapshots");

    /**
     * Fetch JPEG snapshot from camera URL.
     */
    private byte[] fetchSnapshot() throws Exception {
        // Frischer HttpClient pro Request — verhindert "selector manager closed"
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(snapshotUrl))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
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

        // Frischer HttpClient pro Request — verhindert "selector manager closed"
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
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
