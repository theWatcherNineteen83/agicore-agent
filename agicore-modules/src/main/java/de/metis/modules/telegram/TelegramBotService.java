package de.metis.modules.telegram;

import de.metis.kernel.persistence.KnowledgeStore;
import de.metis.modules.Agent;
import de.metis.modules.persona.Persona;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
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

    public void setKnowledgeStore(KnowledgeStore ks) { this.knowledgeStore = ks; }

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
            String text = null;
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
            // Build EDI persona prompt
            String prompt = Persona.systemPrompt()
                    + "\n\nConversation with " + userName + " on Telegram:"
                    + (context.isEmpty() ? "\n(new conversation)" : "\n" + context)
                    + "\n\n" + userName + ": " + text + "\n\nEDI:";

            // Call Ollama directly for chat completion
            String response = callOllama(prompt);
            if (response == null || response.isBlank()) {
                response = "I'm here, but I need a moment. Please try again.";
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
     * Call Ollama /api/generate for a direct LLM completion.
     */
    private String callOllama(String prompt) throws Exception {
        String jsonBody = String.format("""
                {"model":"mistral-small3.1:24b","prompt":%s,"stream":false,
                 "options":{"temperature":0.8,"top_p":0.9,"num_predict":512}}
                """, escapeJson(prompt));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://192.168.22.204:11434/api/generate"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            LOG.warning("Ollama returned " + resp.statusCode());
            return null;
        }

        LOG.fine("Ollama response received, " + resp.body().length() + " bytes");
        // Extract "response" field from JSON
        String body = resp.body();
        String search = "\"response\":\"";
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
     * Build a clean response from the agent's output.
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

        // Trim if excessively long (Telegram limit is 4096)
        if (cleaned.length() > 4000) {
            cleaned = cleaned.substring(0, 4000) + "…";
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
