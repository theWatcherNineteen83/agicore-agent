package de.metis.modules.telegram;

import de.metis.kernel.persistence.KnowledgeStore;
import de.metis.modules.Agent;
import de.metis.modules.persona.Persona;
import de.metis.modules.chat.KnowledgeReplyService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Telegram Bot integration for Metis.
 * <p>
 * Uses long-polling (getUpdates) to receive messages, processes them through
 * Metis's EDI persona and conversation pipeline, and sends responses via sendMessage.
 * <p>
 * No external dependencies — uses only Java's built-in HttpClient.
 */
public class TelegramBotService {

    private static final Logger LOG = Logger.getLogger(TelegramBotService.class.getName());
    private static final String API_BASE = "https://api.telegram.org/bot";
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);

    private final String token;
    private final String apiUrl;
    private final Agent agent;
    private final HttpClient http;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private KnowledgeStore knowledgeStore;
    private KnowledgeReplyService knowledgeReply;
    private Thread pollingThread;
    private long lastUpdateId = 0;

    // Rate limiting
    private Instant lastSentMessage = Instant.EPOCH;
    private static final Duration MIN_SEND_INTERVAL = Duration.ofMillis(500);

    public TelegramBotService(String token, Agent agent) {
        this.token = token;
        this.apiUrl = API_BASE + token;
        this.agent = agent;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void setKnowledgeStore(KnowledgeStore ks) {
        this.knowledgeStore = ks;
        this.knowledgeReply = new KnowledgeReplyService(ks);
    }

    /**
     * Start the long-polling loop in a daemon thread.
     */
    public void start() {
        if (running.getAndSet(true)) {
            LOG.warning("TelegramBotService already running");
            return;
        }

        pollingThread = Thread.ofVirtual().name("telegram-poll").start(this::pollingLoop);
        LOG.info("Telegram bot started (token: " + token.substring(0, 8) + "…)");
    }

    /**
     * Stop the polling loop.
     */
    public void stop() {
        running.set(false);
        if (pollingThread != null) {
            pollingThread.interrupt();
        }
        LOG.info("Telegram bot stopped");
    }

    /**
     * Main long-polling loop.
     */
    private void pollingLoop() {
        // Delete any pending webhook to enable getUpdates
        deleteWebhook();

        int cyclesSinceLog = 0;
        LOG.info("Telegram polling loop started, lastUpdateId=" + lastUpdateId);
        while (running.get()) {
            try {
                LOG.fine("Polling for updates... (offset=" + (lastUpdateId + 1) + ")");
                var updates = getUpdates();
                if (updates != null) {
                    int count = updates.split("\"update_id\"").length - 1;
                    if (count > 0) {
                        LOG.info("Received " + count + " Telegram updates");
                    }
                    processUpdates(updates);
                } else {
                    LOG.warning("getUpdates returned null — API may be unreachable");
                }
                cyclesSinceLog++;
                if (cyclesSinceLog % 3 == 0) {
                    LOG.info("Telegram poll alive, cycles=" + cyclesSinceLog + ", lastUpdateId=" + lastUpdateId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.warning("Polling error: " + e.getClass().getSimpleName() + " — " + e.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
            }
        }
        LOG.info("Telegram polling loop exited");
    }

    // ── Telegram API methods ──────────────────────────────────────

    /**
     * Delete any active webhook to enable getUpdates polling.
     */
    private void deleteWebhook() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/deleteWebhook"))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();
            http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            LOG.warning("deleteWebhook failed: " + e.getMessage());
        }
    }

    /**
     * Long-poll getUpdates with offset tracking.
     */
    private String getUpdates() throws Exception {
        String url = apiUrl + "/getUpdates?timeout=10&offset=" + (lastUpdateId + 1);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            LOG.warning("getUpdates returned " + resp.statusCode() + ": " + resp.body());
            return null;
        }
        String body = resp.body();
        if (body.contains("\"result\":[")) {
            int count = body.split("\"update_id\"").length - 1;
            LOG.fine("Poll: " + count + " updates received");
        }
        return body;
    }

    /**
     * Send a message to a Telegram chat.
     */
    public void sendMessage(long chatId, String text) {
        // Rate limit
        Duration sinceLast = Duration.between(lastSentMessage, Instant.now());
        if (sinceLast.compareTo(MIN_SEND_INTERVAL) < 0) {
            try { Thread.sleep(MIN_SEND_INTERVAL.minus(sinceLast).toMillis()); } 
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }

        try {
            String json = """
                    {"chat_id":%d,"text":%s}
                    """.formatted(chatId, escapeJson(text));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/sendMessage"))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warning("sendMessage failed: " + resp.statusCode() + " " + resp.body());
            }
            lastSentMessage = Instant.now();
        } catch (Exception e) {
            LOG.warning("sendMessage error: " + e.getMessage());
        }
    }

    // ── Update processing ─────────────────────────────────────────

    /**
     * Parse and process incoming updates from getUpdates response.
     * Extracts update_id, chat_id, and text using simple string operations
     * (no JSON library dependency).
     */
    private void processUpdates(String json) {
        // Simple extraction: find each "update_id" and extract surrounding fields
        int pos = 0;
        while (pos < json.length() && running.get()) {
            int uidStart = json.indexOf("\"update_id\":", pos);
            if (uidStart < 0) break;
            uidStart += "\"update_id\":".length();
            
            // Parse update_id (number)
            long updateId = 0;
            while (uidStart < json.length() && (Character.isWhitespace(json.charAt(uidStart)))) uidStart++;
            StringBuilder numBuf = new StringBuilder();
            while (uidStart < json.length() && Character.isDigit(json.charAt(uidStart))) {
                numBuf.append(json.charAt(uidStart++));
            }
            if (numBuf.length() > 0) updateId = Long.parseLong(numBuf.toString());
            
            // Update lastUpdateId
            if (updateId > lastUpdateId) {
                lastUpdateId = updateId;
            }
            
            // Extract chat_id from "chat":{"id":NEXT_NUMBER
            long chatId = 0;
            int chatIdx = json.indexOf("\"chat\":{", uidStart);
            if (chatIdx > 0) {
                int idIdx = json.indexOf("\"id\":", chatIdx);
                if (idIdx > 0) {
                    idIdx += "\"id\":".length();
                    while (idIdx < json.length() && Character.isWhitespace(json.charAt(idIdx))) idIdx++;
                    StringBuilder idBuf = new StringBuilder();
                    while (idIdx < json.length() && Character.isDigit(json.charAt(idIdx))) {
                        idBuf.append(json.charAt(idIdx++));
                    }
                    if (idBuf.length() > 0) chatId = Long.parseLong(idBuf.toString());
                }
            }
            
            // Extract text from "text":"..."
            // Also check for voice message ("voice":{"file_id":"..."})
            String text = null;
            String voiceFileId = null;
            
            // Check for voice message first
            int voiceIdx = json.indexOf("\"voice\":{", uidStart);
            if (voiceIdx > 0) {
                voiceFileId = extractJsonString(json.substring(voiceIdx, Math.min(voiceIdx + 200, json.length())), "file_id");
                if (voiceFileId != null) {
                    LOG.info("Voice message detected, file_id=" + voiceFileId);
                    text = processVoiceMessage(voiceFileId);
                }
            }
            
            // Fallback: text message
            if (text == null) {
            int textIdx = json.indexOf("\"text\":\"", uidStart);
            if (textIdx > 0) {
                textIdx += "\"text\":\"".length();
                StringBuilder textBuf = new StringBuilder();
                for (int i = textIdx; i < json.length(); i++) {
                    char c = json.charAt(i);
                    if (c == '\\' && i + 1 < json.length()) {
                        char next = json.charAt(i + 1);
                        switch (next) {
                            case 'n' -> { textBuf.append('\n'); i++; }
                            case 't' -> { textBuf.append('\t'); i++; }
                            case '"' -> { textBuf.append('"'); i++; }
                            case '\\' -> { textBuf.append('\\'); i++; }
                            default -> textBuf.append(c);
                        }
                    } else if (c == '"') {
                        break;
                    } else {
                        textBuf.append(c);
                    }
                }
                text = textBuf.toString();
            }
            } // end text==null block
            
            // Extract first_name from "from":{"id":...,"first_name":"..."
            String firstName = null;
            int fromIdx = json.indexOf("\"from\":{", uidStart);
            if (fromIdx > 0) {
                int fnIdx = json.indexOf("\"first_name\":\"", fromIdx);
                if (fnIdx > 0) {
                    fnIdx += "\"first_name\":\"".length();
                    StringBuilder fnBuf = new StringBuilder();
                    for (int i = fnIdx; i < json.length() && json.charAt(i) != '"'; i++) {
                        fnBuf.append(json.charAt(i));
                    }
                    firstName = fnBuf.toString();
                }
            }
            
            if (firstName == null) firstName = "User";
            
            // Process the message if we have chatId and text
            if (chatId > 0 && text != null && !text.isBlank()) {
                LOG.info("Telegram [" + chatId + "|" + firstName + "]: \"" + truncate(text, 80) + "\"");
                String response = processMessage(chatId, firstName, text);
                if (response != null && !response.isBlank()) {
                    sendMessage(chatId, response);
                }
            }
            
            pos = uidStart; // advance past current update_id
        }
    }

    /**
     * Process an incoming message directly via Ollama LLM for conversational response.
     * Uses the EDI persona and conversation context, bypassing the agent's action pipeline.
     */
    private String processMessage(long chatId, String userName, String text) {
        String sessionId = "telegram:" + chatId;

        // Load conversation context
        String context = "";
        if (knowledgeStore != null) {
            context = knowledgeStore.conversationSummary(sessionId, 10);
            knowledgeStore.saveConversationMessage(sessionId, "user", userName + ": " + text);
        }

        try {
            // 1st: Try answering from Metis's own knowledge
            String response = null;
            if (knowledgeReply != null) {
                response = knowledgeReply.tryAnswer(text);
                if (response != null) {
                    LOG.info("Knowledge-based reply for: " + truncate(text, 50));
                }
            }

            // 2nd: Fall back to LLM
            if (response == null) {
                String chatPrompt = buildChatPrompt(userName, text, context);
                response = callOllama(chatPrompt);
                if (response == null || response.isBlank()) {
                    response = "I'm here, but I need a moment. Please try again.";
                }
            }

            // Clean up response
            response = buildResponse(response, System.currentTimeMillis());

            // Save to conversation history
            if (knowledgeStore != null) {
                knowledgeStore.saveConversationMessage(sessionId, "assistant", response);
            }

            return response;
        } catch (Exception e) {
            LOG.warning("Chat processing error: " + e.getMessage());
            return "I encountered an error processing that. Please try again.";
        }
    }

    /**
     * Format user message with minimal context for LLM chat.
     * System persona is handled by the system role in /api/chat.
     */
    private String buildChatPrompt(String userName, String text, String context) {
        if (context == null || context.isEmpty()) {
            return userName + " asks: " + text;
        }
        return "Previous conversation:\n" + context + "\n\n" + userName + " asks: " + text;
    }

    /**
     * Call Ollama /api/chat for a conversational response.
     * Uses proper chat roles (system/user/assistant) to avoid prompt leakage.
     */
    private String callOllama(String prompt) throws Exception {
        // Build proper chat messages instead of raw prompt
        String systemMsg = Persona.systemPrompt().replace("\"", "\\\"").replace("\n", "\\n");
        String userMsg = prompt.replace("\"", "\\\"").replace("\n", "\\n");
        
        String jsonBody = String.format("""
                {"model":"medgemma1.5:latest","messages":[
                  {"role":"system","content":"%s"},
                  {"role":"user","content":"%s"}
                ],"stream":false,
                 "options":{"temperature":0.8,"top_p":0.9,"num_predict":512},
                 "keep_alive":0}
                """, systemMsg, userMsg);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://192.168.22.204:11434/api/chat"))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            LOG.warning("Ollama returned " + resp.statusCode());
            return null;
        }

        LOG.fine("Ollama chat response received, " + resp.body().length() + " bytes");
        // Extract "content" field from "message" object in /api/chat response
        String body = resp.body();
        String search = "\"content\":\"";
        int start = body.indexOf(search);
        if (start < 0) return null;
        start += search.length();

        StringBuilder result = new StringBuilder();
        for (int i = start; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char next = body.charAt(i + 1);
                switch (next) {
                    case 'n' -> { result.append('\n'); i++; }
                    case 't' -> { result.append('\t'); i++; }
                    case '"' -> { result.append('"'); i++; }
                    case '\\' -> { result.append('\\'); i++; }
                    default -> result.append(c);
                }
            } else if (c == '"') {
                break;
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    /**
     * Process a Telegram voice message: download OGG, convert to WAV, transcribe.
     * @return transcribed text, or null on failure
     */
    private String processVoiceMessage(String fileId) {
        try {
            // 1. Get file path from Telegram
            String filePath = getTelegramFilePath(fileId);
            if (filePath == null) {
                LOG.warning("Could not get file path for voice message " + fileId);
                return null;
            }
            LOG.info("Voice file path: " + filePath);

            // 2. Download voice file (.ogg Opus)
            Path oggFile = Path.of("/tmp", "telegram-voice-" + fileId + ".ogg");
            String downloadUrl = "https://api.telegram.org/file/bot" + token + "/" + filePath;
            downloadFile(downloadUrl, oggFile);

            if (!Files.exists(oggFile) || Files.size(oggFile) < 100) {
                LOG.warning("Voice download failed or too small: " + oggFile);
                return null;
            }
            LOG.info("Downloaded voice: " + Files.size(oggFile) + " bytes");

            // 3. Convert OGG → 16kHz mono WAV (for Whisper)
            Path wavFile = Path.of("/tmp", "telegram-voice-" + fileId + ".wav");
            ProcessBuilder ffmpeg = new ProcessBuilder(
                    "ffmpeg", "-y", "-i", oggFile.toString(),
                    "-ar", "16000", "-ac", "1", "-sample_fmt", "s16",
                    wavFile.toString()
            );
            ffmpeg.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = ffmpeg.start();
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);

            if (!Files.exists(wavFile)) {
                LOG.warning("FFmpeg conversion failed");
                return null;
            }

            // 4. Transcribe with Whisper
            Path outDir = Path.of("/tmp", "telegram-transcribe");
            Files.createDirectories(outDir);
            ProcessBuilder whisper = new ProcessBuilder(
                    "whisper", wavFile.toString(),
                    "--model", "small", "--language", "de",
                    "--output_dir", outDir.toString(),
                    "--output_format", "txt"
            );
            Map<String,String> env = whisper.environment();
            env.put("PATH", System.getenv("PATH") + ":/data/prometheus/.local/bin");
            whisper.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process wp = whisper.start();
            wp.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);

            // 5. Read transcription
            String fileName = wavFile.getFileName().toString().replace(".wav", "");
            Path txtFile = outDir.resolve(fileName + ".txt");
            String text = "";
            if (Files.exists(txtFile)) {
                text = Files.readString(txtFile).trim();
            } else {
                // Try alternate naming
                try (var stream = Files.list(outDir)) {
                    text = stream.filter(f -> f.getFileName().toString().endsWith(".txt"))
                            .findFirst()
                            .map(f -> { try { return Files.readString(f).trim(); } catch (IOException e) { return ""; } })
                            .orElse("");
                }
            }

            // Cleanup temp files
            try { Files.deleteIfExists(oggFile); } catch (IOException ignored) {}
            try { Files.deleteIfExists(wavFile); } catch (IOException ignored) {}

            if (text.isBlank()) {
                LOG.info("Voice transcription: (silence)");
                return null;
            }

            LOG.info("Voice transcription: \"" + text + "\"");
            return "[Voice] " + text;

        } catch (Exception e) {
            LOG.warning("Voice processing error: " + e.getMessage());
            return null;
        }
    }

    private String getTelegramFilePath(String fileId) throws Exception {
        String json = """
                {"file_id":"%s"}
                """.formatted(fileId);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/getFile"))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;
        return extractJsonString(resp.body(), "file_path");
    }

    // ── Voice Reply (TTS → sendVoice) ──

    /** Send a voice reply via Piper TTS + Telegram sendVoice. */
    public void sendVoiceReply(long chatId, String text) {
        try {
            Path wavFile = Files.createTempFile("metis-tts-", ".wav");
            ProcessBuilder piper = new ProcessBuilder(
                    "piper", "--model",
                    "/usr/local/share/piper-voices/de_DE-thorsten-medium.onnx",
                    "--output_file", wavFile.toString()
            );
            piper.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process pp = piper.start();
            pp.getOutputStream().write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            pp.getOutputStream().close();
            pp.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);

            if (!Files.exists(wavFile) || Files.size(wavFile) < 1000) {
                sendMessage(chatId, text); return;
            }

            Path oggFile = Files.createTempFile("metis-voice-", ".ogg");
            new ProcessBuilder("ffmpeg", "-y", "-i", wavFile.toString(),
                    "-c:a", "libopus", "-b:a", "32k", oggFile.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor(10, java.util.concurrent.TimeUnit.SECONDS);

            if (Files.exists(oggFile) && Files.size(oggFile) > 100) {
                sendTelegramVoice(chatId, oggFile);
            } else {
                sendMessage(chatId, text);
            }
            try { Files.deleteIfExists(wavFile); } catch (IOException ignored) {}
            try { Files.deleteIfExists(oggFile); } catch (IOException ignored) {}
        } catch (Exception e) {
            LOG.warning("sendVoiceReply error: " + e.getMessage());
            sendMessage(chatId, text);
        }
    }

    private void sendTelegramVoice(long chatId, Path oggFile) throws Exception {
        String boundary = "MetisBoundary" + System.currentTimeMillis();
        var bos = new java.io.ByteArrayOutputStream();
        bos.write(("--" + boundary + "\r\n").getBytes());
        bos.write("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n".getBytes());
        bos.write((chatId + "\r\n").getBytes());
        bos.write(("--" + boundary + "\r\n").getBytes());
        bos.write("Content-Disposition: form-data; name=\"voice\"; filename=\"reply.ogg\"\r\n".getBytes());
        bos.write("Content-Type: audio/ogg\r\n\r\n".getBytes());
        bos.write(Files.readAllBytes(oggFile));
        bos.write(("\r\n--" + boundary + "--\r\n").getBytes());

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/sendVoice"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bos.toByteArray()))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            LOG.warning("sendVoice failed: " + resp.statusCode());
            throw new IOException("Telegram sendVoice returned " + resp.statusCode());
        }
        LOG.info(() -> "Voice reply sent (" + safeSize(oggFile) + " bytes)");
    }

    private static long safeSize(Path p) { try { return Files.size(p); } catch (IOException e) { return -1; } }

    private void downloadFile(String url, Path dest) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() == 200) {
            Files.write(dest, resp.body());
        }
    }

    /**
     * Build a clean Telegram-ready response from the agent's output.
     * Strips markdown artifacts that phi4 may generate despite instructions.
     */
    private String buildResponse(String agentOutput, long startMs) {
        if (agentOutput == null || agentOutput.isBlank()) {
            return "I'm here, but I don't have a specific answer for that.";
        }

        String cleaned = agentOutput.strip();

        // Remove any "EDI:" prefix if present
        if (cleaned.startsWith("EDI:")) {
            cleaned = cleaned.substring(4).strip();
        }

        // Strip markdown artifacts (safety net — phi4 sometimes ignores formatting rules)
        cleaned = cleaned
            .replaceAll("```[^`]*```", "")          // code blocks
            .replaceAll("`([^`]+)`", "$1")          // inline code
            .replaceAll("\\*\\*([^*]+)\\*\\*", "$1") // bold
            .replaceAll("\\*([^*]+)\\*", "$1")      // italic
            .replaceAll("##+\\s*", "")              // headers
            .replaceAll("\\|.*\\|", "")             // table rows
            .replaceAll("\\n{3,}", "\n\n")          // excessive newlines
            .strip();

        // Trim if excessively long (Telegram limit is 4096)
        if (cleaned.length() > 4000) {
            cleaned = cleaned.substring(0, 4000) + "…";
        }

        // If after cleanup we have nothing, fall back
        if (cleaned.isBlank()) {
            return "I understand, but I need a moment to process that.";
        }

        return cleaned;
    }

    // ── JSON helpers ──────────────────────────────────────────────

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) {
            // Try with optional spaces: "key": "value"
            search = "\"" + key + "\": \"";
            start = json.indexOf(search);
        }
        if (start < 0) return null;
        start += search.length();

        StringBuilder val = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"' -> { val.append('"'); i++; }
                    case '\\' -> { val.append('\\'); i++; }
                    case 'n' -> { val.append('\n'); i++; }
                    case 't' -> { val.append('\t'); i++; }
                    case 'r' -> { val.append('\r'); i++; }
                    case 'u' -> {
                        if (i + 5 < json.length()) {
                            String hex = json.substring(i + 2, i + 6);
                            val.append((char) Integer.parseInt(hex, 16));
                            i += 5;
                        } else { val.append(c); }
                    }
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

    private long extractJsonLong(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return 0;
        start += search.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        StringBuilder num = new StringBuilder();
        while (start < json.length() && (Character.isDigit(json.charAt(start)) || json.charAt(start) == '-')) {
            num.append(json.charAt(start++));
        }
        try { return Long.parseLong(num.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private int findMatchingBracket(String json, int openPos) {
        char open = json.charAt(openPos);
        char close = open == '[' ? ']' : open == '{' ? '}' : open;
        int depth = 0;
        boolean inString = false;
        for (int i = openPos; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) inString = !inString;
            if (inString) continue;
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private int findMatchingBrace(String json, int openBrace) {
        return findMatchingBracket(json, openBrace);
    }

    private String escapeJson(String s) {
        StringBuilder sb = new StringBuilder("\"");
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
        return sb.append('"').toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
