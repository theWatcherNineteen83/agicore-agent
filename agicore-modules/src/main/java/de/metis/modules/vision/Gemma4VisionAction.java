package de.metis.modules.vision;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Gemma4 Vision API action for Metis.
 * Calls the Gemma4 Vision HTTP API (port 11439) via Ollama.
 */
public class Gemma4VisionAction implements Action {

    private static final Logger LOG = Logger.getLogger(Gemma4VisionAction.class.getName());
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String name;
    private final String apiUrl;
    private final String task;
    private final String prompt;
    private final String imagePath;

    public static Gemma4VisionAction understand(String apiUrl, String imagePath, String question) {
        return new Gemma4VisionAction("vision.understand", apiUrl, "understand", question, imagePath);
    }
    public static Gemma4VisionAction caption(String apiUrl, String imagePath) {
        return new Gemma4VisionAction("vision.caption", apiUrl, "caption", "", imagePath);
    }
    public static Gemma4VisionAction detect(String apiUrl, String imagePath) {
        return new Gemma4VisionAction("vision.detect", apiUrl, "detect", "", imagePath);
    }
    public static Gemma4VisionAction ocr(String apiUrl, String imagePath) {
        return new Gemma4VisionAction("vision.ocr", apiUrl, "ocr", "", imagePath);
    }
    public static Gemma4VisionAction qa(String apiUrl, String imagePath, String question) {
        return new Gemma4VisionAction("vision.qa", apiUrl, "qa", question, imagePath);
    }

    public Gemma4VisionAction(String name, String apiUrl, String task, String prompt, String imagePath) {
        this.name = name;
        this.apiUrl = apiUrl.replaceAll("/$", "");
        this.task = task;
        this.prompt = prompt;
        this.imagePath = imagePath;
    }

    @Override public String name() { return name; }
    @Override public String category() { return "read"; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            String body = String.format("{\"task\":\"%s\",\"prompt\":\"%s\",\"image_path\":\"%s\"}",
                    task, escapeJson(prompt), escapeJson(imagePath));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/v1/vision/" + task))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(180))
                    .build();

            HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            long ms = Duration.between(start, Instant.now()).toMillis();

            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                String respBody = resp.body();
                if (respBody.length() > 2000) respBody = respBody.substring(0, 1997) + "...";
                LOG.info(() -> "Gemma4 " + task + " → " + resp.statusCode() + " (" + ms + "ms)");
                return ActionResult.ok(name, respBody, start);
            } else {
                return ActionResult.fail(name, "Gemma4 " + resp.statusCode() + ": " + resp.body(), start);
            }
        } catch (Exception e) {
            LOG.warning("Gemma4 action failed: " + e.getMessage());
            return ActionResult.fail(name, e.getMessage(), start);
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
