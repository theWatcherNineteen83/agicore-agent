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
 * Neural Text-to-Speech via Qwen3-TTS microservice on miniedi.
 * <p>
 * Supports 10 languages plus Chinese dialects including
 * <b>Shanghainese (Wu)</b>. Uses the 0.6B CustomVoice model with
 * 9 premium speaker voices running CPU-only on miniedi:11741.
 * <p>
 * Speakers: serena (Chinese female, default), vivian, uncle_fu, aiden,
 * dylan, eric, ono_anna, ryan, sohee.
 * <p>
 * Category: write (generates audio output).
 * <p>
 * Design: Shanghainese language learning, 2026-06-05.
 *
 * @see QwenAsrSttAction for the STT counterpart
 */
public class QwenTtsAction implements Action {

    public static final String NAME = "tts-qwen";
    private static final Logger LOG = Logger.getLogger(QwenTtsAction.class.getName());
    private static final String DEFAULT_TTS_URL = "http://miniedi:11741/tts";
    private static final String DEFAULT_SPEAKER = "serena";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String text;
    private final String speaker;
    private final String instruction;
    private final String ttsUrl;

    /**
     * @param text    text to speak (Chinese characters for Shanghainese)
     * @param speaker voice speaker (default: serena)
     * @param instruction style instruction (e.g. "Speak naturally in Shanghainese Wu dialect.")
     */
    public QwenTtsAction(String text, String speaker, String instruction) {
        this(text, speaker, instruction, DEFAULT_TTS_URL);
    }

    public QwenTtsAction(String text) {
        this(text, DEFAULT_SPEAKER, "Speak naturally in Chinese.");
    }

    public QwenTtsAction(String text, String speaker, String instruction, String ttsUrl) {
        this.text = text;
        this.speaker = speaker != null ? speaker : DEFAULT_SPEAKER;
        this.instruction = instruction != null ? instruction : "Speak naturally in Chinese.";
        this.ttsUrl = ttsUrl;
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "write"; }

    @Override
    public ApprovalLevel approvalLevel() {
        return ApprovalLevel.AUTO; // purely local audio output
    }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            if (text == null || text.isBlank()) {
                return ActionResult.fail(NAME, "Text is empty", start);
            }

            // Build JSON request
            String json = String.format(
                    "{\"text\":\"%s\",\"speaker\":\"%s\",\"instruction\":\"%s\"}",
                    escapeJson(text.trim()),
                    escapeJson(speaker),
                    escapeJson(instruction)
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ttsUrl))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return ActionResult.fail(NAME, "TTS HTTP " + response.statusCode() + ": "
                        + response.body().substring(0, Math.min(200, response.body().length())), start);
            }

            // Parse JSON: {"audio_base64":"...", "sample_rate":24000, "duration_s":2.2, ...}
            String audioB64 = extractJsonField(response.body(), "audio_base64");

            if (audioB64 == null || audioB64.isEmpty()) {
                return ActionResult.fail(NAME, "No audio in TTS response", start);
            }

            // Decode and save to temp file
            byte[] wavBytes = Base64.getDecoder().decode(audioB64);
            Path outputFile = Files.createTempFile("metis-tts-qwen-", ".wav");
            Files.write(outputFile, wavBytes);

            String durationStr = extractJsonField(response.body(), "duration_s");
            long ms = Duration.between(start, Instant.now()).toMillis();

            LOG.info(() -> "Qwen3-TTS [" + speaker + "]: \""
                    + text.substring(0, Math.min(50, text.length()))
                    + "...\" → " + wavBytes.length + " bytes, " + ms + "ms");

            return ActionResult.ok(NAME,
                    String.format("TTS: %d bytes, %ss (%dms) → %s | speaker=%s",
                            wavBytes.length,
                            durationStr != null ? durationStr : "?",
                            ms, outputFile, speaker),
                    start);

        } catch (Exception e) {
            LOG.warning("Qwen3-TTS failed: " + e.getMessage());
            return ActionResult.fail(NAME, e.getMessage(), start);
        }
    }

    // ── helpers ──

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String extractJsonField(String json, String field) {
        if (json == null) return null;
        String search = "\"" + field + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        idx += search.length();
        while (idx < json.length() && Character.isWhitespace(json.charAt(idx))) idx++;
        if (idx >= json.length()) return null;
        if (json.charAt(idx) == '"') {
            int end = json.indexOf('"', idx + 1);
            if (end < 0) return json.substring(idx + 1);
            return json.substring(idx + 1, end);
        } else {
            int end = idx;
            while (end < json.length() && !Character.isWhitespace(json.charAt(end))
                    && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
            return json.substring(idx, end);
        }
    }
}
