package de.metis.kernel.action;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Löst CAPTCHAs über 2Captcha API (primär) oder OCR-Fallback.
 * <p>
 * Backends:
 * <ul>
 *   <li><b>2Captcha</b> (empfohlen): reCAPTCHA v2/v3, hCaptcha, Image CAPTCHA.
 *       Benötigt API-Key (System-Property {@code metis.captcha.key} oder
 *       Environment {@code CAPTCHA_API_KEY}). Kosten ~$3/1000 Captchas.</li>
 *   <li><b>OCR-Fallback</b> (kostenlos): Für einfache Text-CAPTCHAs.
 *       Nutzt Image-Processing (Schwellwert + Kontrast) + Tesseract-OCR
 *       falls installiert, sonst manuelle Eingabe-Aufforderung.</li>
 * </ul>
 * <p>
 * Category: read (external service). Approval: AUTO (nur Captcha-Lösen, kein Kauf).
 */
public class CaptchaSolverAction implements Action {

    private static final Logger LOG = Logger.getLogger(CaptchaSolverAction.class.getName());
    public static final String NAME = "captcha";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final String API_BASE = "https://api.2captcha.com";

    private final String captchaImageUrl;   // URL or base64 data URI of the CAPTCHA image
    private final String captchaSiteKey;    // for reCAPTCHA/hCaptcha
    private final String captchaPageUrl;    // page where the CAPTCHA appears
    private final String captchaType;       // "image", "recaptcha_v2", "recaptcha_v3", "hcaptcha"

    /**
     * @param type     CAPTCHA type: image, recaptcha_v2, recaptcha_v3, hcaptcha
     * @param imageUrl URL of the CAPTCHA image (or base64 data URI)
     * @param siteKey  site key for reCAPTCHA/hCaptcha
     * @param pageUrl  page URL where the CAPTCHA is embedded
     */
    public CaptchaSolverAction(String type, String imageUrl, String siteKey, String pageUrl) {
        this.captchaType = type != null ? type.toLowerCase() : "image";
        this.captchaImageUrl = imageUrl;
        this.captchaSiteKey = siteKey;
        this.captchaPageUrl = pageUrl;
    }

    /** Convenience: image CAPTCHA from URL. */
    public CaptchaSolverAction(String imageUrl) {
        this("image", imageUrl, null, null);
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "read"; }
    @Override public ApprovalLevel approvalLevel() { return ApprovalLevel.AUTO; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        String apiKey = resolveApiKey();

        if (apiKey != null && !apiKey.isBlank()) {
            // Primary: 2Captcha API
            try {
                return solveWith2Captcha(apiKey, start);
            } catch (Exception e) {
                LOG.warning("2Captcha failed: " + e.getMessage() + " — falling back to OCR");
            }
        }

        // Fallback: OCR
        try {
            return solveWithOcr(start);
        } catch (Exception e) {
            return ActionResult.fail(NAME,
                    "CAPTCHA solve failed (both 2Captcha and OCR): " + e.getMessage(), start);
        }
    }

    // ── 2Captcha API ─────────────────────────────────────────────────

    private ActionResult solveWith2Captcha(String apiKey, Instant start) throws Exception {
        String taskId = createTask(apiKey);
        LOG.info("2Captcha task created: " + taskId);

        // Poll for result (max 120s for image, 300s for reCAPTCHA)
        int maxWait = captchaType.startsWith("recaptcha") ? 300 : 120;
        String solution = waitForResult(apiKey, taskId, maxWait);

        if (solution == null || solution.isBlank()) {
            return ActionResult.fail(NAME, "2Captcha timeout after " + maxWait + "s (task: " + taskId + ")", start);
        }

        return ActionResult.ok(NAME, "CAPTCHA solved via 2Captcha: " + solution, start);
    }

    private String createTask(String apiKey) throws Exception {
        String jsonBody;
        switch (captchaType) {
            case "recaptcha_v2":
            case "recaptcha_v3":
                jsonBody = String.format(
                        "{\"clientKey\":\"%s\",\"task\":{\"type\":\"RecaptchaV2TaskProxyless\"," +
                        "\"websiteURL\":\"%s\",\"websiteKey\":\"%s\"}}",
                        apiKey, escapeJson(captchaPageUrl), escapeJson(captchaSiteKey));
                break;
            case "hcaptcha":
                jsonBody = String.format(
                        "{\"clientKey\":\"%s\",\"task\":{\"type\":\"HCaptchaTaskProxyless\"," +
                        "\"websiteURL\":\"%s\",\"websiteKey\":\"%s\"}}",
                        apiKey, escapeJson(captchaPageUrl), escapeJson(captchaSiteKey));
                break;
            default: // image
                String imageBody = resolveImageBody();
                if (imageBody == null) {
                    throw new IllegalStateException("No CAPTCHA image provided");
                }
                jsonBody = String.format(
                        "{\"clientKey\":\"%s\",\"task\":{\"type\":\"ImageToTextTask\"," +
                        "\"body\":\"%s\"}}", apiKey, imageBody);
                break;
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/createTask"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());

        if (node.has("errorId") && node.get("errorId").asInt() != 0) {
            String err = node.has("errorDescription") ? node.get("errorDescription").asText() : "unknown";
            throw new RuntimeException("2Captcha createTask error: " + err);
        }

        return node.get("taskId").asText();
    }

    private String waitForResult(String apiKey, String taskId, int maxWaitSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + maxWaitSeconds * 1000L;

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(Math.min(5000, maxWaitSeconds * 100));

            String body = String.format("{\"clientKey\":\"%s\",\"taskId\":%s}", apiKey, taskId);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/getTaskResult"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());

            if (node.has("status") && "ready".equals(node.get("status").asText())) {
                return node.get("solution").has("text")
                        ? node.get("solution").get("text").asText()
                        : node.get("solution").get("gRecaptchaResponse").asText();
            }
        }

        return null; // timeout
    }

    // ── OCR Fallback ─────────────────────────────────────────────────

    private ActionResult solveWithOcr(Instant start) throws Exception {
        if (captchaImageUrl == null || captchaImageUrl.isBlank()) {
            return ActionResult.fail(NAME, "No CAPTCHA image URL for OCR fallback", start);
        }

        // Download the CAPTCHA image
        byte[] imageBytes;
        if (captchaImageUrl.startsWith("data:")) {
            // base64 data URI: data:image/png;base64,...
            String b64 = captchaImageUrl.substring(captchaImageUrl.indexOf(",") + 1);
            imageBytes = Base64.getDecoder().decode(b64);
        } else {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(captchaImageUrl))
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                return ActionResult.fail(NAME,
                        "Failed to download CAPTCHA image: HTTP " + resp.statusCode(), start);
            }
            imageBytes = resp.body();
        }

        // Save to temp file for Tesseract
        Path tmpFile = Files.createTempFile("captcha_", ".png");
        Files.write(tmpFile, imageBytes);

        // Try Tesseract OCR
        Optional<String> tesseractResult = tryTesseract(tmpFile);
        Files.deleteIfExists(tmpFile);

        if (tesseractResult.isPresent()) {
            String text = tesseractResult.get().trim().replaceAll("\\s+", "");
            if (!text.isEmpty()) {
                return ActionResult.ok(NAME, "CAPTCHA solved via OCR: " + text, start);
            }
        }

        // Last resort: return image as base64 for manual solving
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        return ActionResult.fail(NAME,
                "CAPTCHA OCR failed — needs manual solving. Image (base64, "
                + imageBytes.length + " bytes): " + base64Image.substring(0, Math.min(100, base64Image.length()))
                + "...", start);
    }

    private Optional<String> tryTesseract(Path imageFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "tesseract", imageFile.toAbsolutePath().toString(),
                    "stdout", "-l", "eng", "--psm", "7"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            return Optional.of(output.strip());
        } catch (Exception e) {
            LOG.fine("Tesseract not available: " + e.getMessage());
            return Optional.empty();
        }
    }

    // ── Utilities ────────────────────────────────────────────────────

    private String resolveApiKey() {
        String key = System.getProperty("metis.captcha.key");
        if (key == null || key.isBlank()) {
            key = System.getenv("CAPTCHA_API_KEY");
        }
        return key;
    }

    private String resolveImageBody() throws Exception {
        if (captchaImageUrl == null) return null;
        if (captchaImageUrl.startsWith("data:")) {
            // Already base64-encoded data URI
            return captchaImageUrl.substring(captchaImageUrl.indexOf(",") + 1);
        }
        // Download and base64-encode
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(captchaImageUrl))
                .timeout(Duration.ofSeconds(10))
                .GET().build();
        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        return Base64.getEncoder().encodeToString(resp.body());
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public String toString() {
        return "CaptchaSolverAction[" + captchaType + "]";
    }
}
