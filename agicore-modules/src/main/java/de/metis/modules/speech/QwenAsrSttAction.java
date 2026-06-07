package de.metis.modules.speech;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.logging.Logger;

/**
 * Speech-to-Text via Qwen3-ASR microservice on miniedi.
 * <p>
 * Supports 52 languages including 22 Chinese dialects, notably
 * <b>Shanghainese (Wu language)</b>. Uses the 0.6B model running CPU-only
 * on miniedi:11740.
 * <p>
 * Category: read (external HTTP, no side effects).
 * <p>
 * Design: Shanghainese language learning, 2026-06-05.
 *
 * @see QwenTtsAction for the TTS counterpart
 */
public class QwenAsrSttAction implements Action {

    public static final String NAME = "stt-qwen-asr";
    private static final Logger LOG = Logger.getLogger(QwenAsrSttAction.class.getName());
    private static final String DEFAULT_STT_URL = "http://miniedi:11740/stt";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Path audioFile;
    private final String language; // "Wu" for Shanghainese, null for auto-detect
    private final String sttUrl;

    /**
     * Create STT action for an audio file.
     *
     * @param audioFile path to WAV audio file to transcribe
     * @param language  language hint or null for auto-detect. Use "Wu" for Shanghainese.
     */
    public QwenAsrSttAction(Path audioFile, String language) {
        this(audioFile, language, DEFAULT_STT_URL);
    }

    public QwenAsrSttAction(Path audioFile, String language, String sttUrl) {
        this.audioFile = audioFile;
        this.language = language;
        this.sttUrl = sttUrl;
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "read"; }

    @Override
    public ApprovalLevel approvalLevel() {
        return ApprovalLevel.AUTO; // purely observational
    }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            if (!Files.exists(audioFile) || Files.size(audioFile) == 0) {
                return ActionResult.fail(NAME, "Audio file missing or empty: " + audioFile, start);
            }

            byte[] audioBytes = Files.readAllBytes(audioFile);

            // Build multipart request
            String boundary = "----MetisStt" + System.currentTimeMillis();
            var bodyBuilder = new java.io.ByteArrayOutputStream();
            String preamble = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n"
                    + "Content-Type: audio/wav\r\n\r\n";
            bodyBuilder.write(preamble.getBytes());
            bodyBuilder.write(audioBytes);
            bodyBuilder.write(("\r\n--" + boundary + "--\r\n").getBytes());

            String url = sttUrl;
            if (language != null && !language.isBlank()) {
                url += "?language=" + java.net.URLEncoder.encode(language, "UTF-8");
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBuilder.toByteArray()))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return ActionResult.fail(NAME, "STT HTTP " + response.statusCode() + ": "
                        + response.body().substring(0, Math.min(200, response.body().length())), start);
            }

            // Parse JSON: {"text":"...", "language":"Wu", "duration_ms":1234}
            String text = extractJsonField(response.body(), "text");
            String detectedLang = extractJsonField(response.body(), "language");

            LOG.info(() -> "Qwen3-ASR [" + (detectedLang != null ? detectedLang : "auto") + "]: '"
                    + truncate(text, 80) + "'");

            return ActionResult.ok(NAME,
                    "STT: " + (text != null ? text : "(silence)")
                    + " | lang=" + (detectedLang != null ? detectedLang : "auto"),
                    start);

        } catch (Exception e) {
            LOG.warning("Qwen3-ASR failed: " + e.getMessage());
            return ActionResult.fail(NAME, e.getMessage(), start);
        }
    }

    // ── helpers ──

    private static String extractJsonField(String json, String field) {
        if (json == null) return null;
        String search = "\"" + field + "\":";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        // skip whitespace
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            if (end < 0) return json.substring(start + 1);
            return json.substring(start + 1, end);
        } else {
            // numeric or other non-string value
            int end = start;
            while (end < json.length() && !Character.isWhitespace(json.charAt(end))
                    && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
            return json.substring(start, end);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
