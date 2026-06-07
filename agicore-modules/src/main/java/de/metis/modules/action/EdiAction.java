package de.metis.modules.action;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * EDI Gateway action — send and receive structured EDI messages.
 * <p>
 * Talks to the EDI Gateway (Python/Flask) running on port 18000.
 * Supports ORDER, INVOICE, DESADV, STATUS message types with SHA-256 checksums.
 * <p>
 * <b>Read actions:</b> list messages, get message by ID<br>
 * <b>Write actions:</b> send message to a partner
 */
public class EdiAction implements Action {

    private static final Logger LOG = Logger.getLogger(EdiAction.class.getName());
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String DEFAULT_GATEWAY = "http://localhost:18000";

    private final String name;
    private final String gatewayUrl;
    private final String mode;     // "send", "receive", "get", "list"
    private final String messageType;
    private final String senderId;
    private final String recipientId;
    private final List<Map<String, Object>> items;
    private final String referenceId;
    private final String messageId; // for "get" mode

    /** Send an EDI message to a partner. */
    public static EdiAction send(String sender, String recipient, String type,
                                  List<Map<String, Object>> items, String refId) {
        return new EdiAction("edi-send", DEFAULT_GATEWAY, "send", type,
                sender, recipient, items, refId, null);
    }

    /** Query one message by ID. */
    public static EdiAction getMessage(String messageId) {
        return new EdiAction("edi-get", DEFAULT_GATEWAY, "get", null,
                null, null, null, null, messageId);
    }

    /** List recent EDI messages. */
    public static EdiAction listMessages() {
        return new EdiAction("edi-list", DEFAULT_GATEWAY, "list", null,
                null, null, null, null, null);
    }

    private EdiAction(String name, String gatewayUrl, String mode, String messageType,
                      String senderId, String recipientId, List<Map<String, Object>> items,
                      String referenceId, String messageId) {
        this.name = name;
        this.gatewayUrl = gatewayUrl;
        this.mode = mode;
        this.messageType = messageType;
        this.senderId = senderId;
        this.recipientId = recipientId;
        this.items = items;
        this.referenceId = referenceId;
        this.messageId = messageId;
    }

    @Override public String name() { return name; }
    @Override public String category() { return "list".equals(mode) || "get".equals(mode) ? "read" : "write"; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            return switch (mode) {
                case "send" -> doSend(start);
                case "get" -> doGet(start);
                case "list" -> doList(start);
                default -> ActionResult.fail(name, "Unknown mode: " + mode, start);
            };
        } catch (Exception e) {
            LOG.warning("EDI action failed: " + e.getMessage());
            return ActionResult.fail(name, "EDI error: " + e.getMessage(), start);
        }
    }

    private ActionResult doSend(Instant start) throws Exception {
        // Build items JSON manually to avoid extra dependencies
        StringBuilder itemsJson = new StringBuilder("[");
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) itemsJson.append(",");
                Map<String, Object> item = items.get(i);
                itemsJson.append("{");
                itemsJson.append("\"lineNumber\":").append(item.get("lineNumber")).append(",");
                itemsJson.append("\"articleId\":\"").append(item.get("articleId")).append("\",");
                itemsJson.append("\"quantity\":").append(item.get("quantity"));
                if (item.containsKey("unitPrice")) {
                    itemsJson.append(",\"unitPrice\":").append(item.get("unitPrice"));
                }
                itemsJson.append("}");
            }
        }
        itemsJson.append("]");

        String body = "{"
                + "\"senderId\":\"" + senderId + "\","
                + "\"recipientId\":\"" + recipientId + "\","
                + "\"messageType\":\"" + messageType + "\","
                + "\"items\":" + itemsJson
                + (referenceId != null ? ",\"referenceId\":\"" + referenceId + "\"" : "")
                + "}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(gatewayUrl + "/edi/send"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 201 || resp.statusCode() == 200) {
            LOG.info("EDI send OK: " + messageType + " → " + recipientId);
            return ActionResult.ok(name, "EDI " + messageType + " sent to " + recipientId
                    + ". Response: " + resp.body(), start);
        }
        return ActionResult.fail(name, "EDI send failed: HTTP " + resp.statusCode()
                + " — " + resp.body(), start);
    }

    private ActionResult doGet(Instant start) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(gatewayUrl + "/edi/message/" + messageId))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 200) {
            return ActionResult.ok(name, "EDI message " + messageId + ": " + resp.body(), start);
        }
        return ActionResult.fail(name, "EDI get failed: HTTP " + resp.statusCode(), start);
    }

    private ActionResult doList(Instant start) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(gatewayUrl + "/edi/messages?limit=10"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 200) {
            return ActionResult.ok(name, "EDI messages: " + resp.body(), start);
        }
        return ActionResult.fail(name, "EDI list failed: HTTP " + resp.statusCode(), start);
    }
}
