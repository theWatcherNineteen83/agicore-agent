package de.metis.modules.chat;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Append-only chat log for learning from conversations.
 * Stores turns as JSONL under metis.chat.log.path.
 * Wire into TelegramBotService and MetisHttpServer for automatic logging.
 */
public class ChatLogService {
    private static final Logger LOG = Logger.getLogger(ChatLogService.class.getName());
    private final Path logPath;
    private final Object lock = new Object();

    public ChatLogService() {
        this(Path.of(System.getProperty("metis.chat.log.path", "/home/prometheus/metis/chat-history.jsonl")));
    }

    public ChatLogService(Path logPath) {
        this.logPath = logPath;
        try { Files.createDirectories(logPath.getParent()); } catch (IOException ignored) {}
    }

    /**
     * Log a chat turn.
     * @param source "telegram" or "http"
     * @param senderId user identifier
     * @param senderName display name
     * @param role "user" or "assistant"
     * @param text message content
     */
    public void log(String source, String senderId, String senderName, String role, String text) {
        String sanitized = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        String entry = String.format(
            "{\"ts\":\"%s\",\"source\":\"%s\",\"senderId\":\"%s\",\"senderName\":\"%s\",\"role\":\"%s\",\"text\":\"%s\"}\n",
            Instant.now().toString(), source, senderId, senderName, role, sanitized
        );
        synchronized (lock) {
            try {
                Files.write(logPath, entry.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                LOG.warning(() -> "ChatLog write failed: " + e.getMessage());
            }
        }
    }

    /**
     * Read recent chat turns (last N lines, up to maxChars).
     * Returns chronologically ordered list.
     */
    public List<String> recentTurns(int maxLines, int maxChars) {
        List<String> result = new ArrayList<>();
        synchronized (lock) {
            try {
                if (!Files.exists(logPath)) return result;
                List<String> all = Files.readAllLines(logPath, StandardCharsets.UTF_8);
                int total = 0;
                for (int i = all.size() - 1; i >= 0 && result.size() < maxLines; i--) {
                    String line = all.get(i);
                    total += line.length();
                    if (total > maxChars) break;
                    result.add(line);
                }
                Collections.reverse(result);
            } catch (IOException e) {
                LOG.warning(() -> "ChatLog read failed: " + e.getMessage());
            }
        }
        return result;
    }

    /**
     * Produces a summary of recent interactions for SelfNarrative.
     */
    public String summarize(int maxLines) {
        List<String> turns = recentTurns(maxLines, 4096);
        if (turns.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Recent conversations:\n");
        for (String t : turns) {
            if (t.contains("\"role\":\"user\"")) {
                sb.append("- User: ");
            } else {
                sb.append("- Metis: ");
            }
            // Extract short preview
            int textStart = t.indexOf("\"text\":\"");
            if (textStart > 0) {
                int textEnd = t.indexOf("\"", textStart + 8);
                String preview = textEnd > textStart ? t.substring(textStart + 8, Math.min(textEnd, textStart + 108)) : "...";
                sb.append(preview).append("\n");
            }
        }
        return sb.toString();
    }
}
