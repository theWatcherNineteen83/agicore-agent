package de.metis.modules.home;

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
 * Home Assistant API access — read states, call services.
 * <p>
 * Gives Metis direct read/write access to the smart home.
 * Uses the HA REST API with long-lived access token.
 * <p>
 * <b>Read actions:</b> get entity state, list entities<br>
 * <b>Write actions (approval-gated):</b> call services (switch, light, etc.)
 */
public class HomeAssistantAction implements Action {

    private static final Logger LOG = Logger.getLogger(HomeAssistantAction.class.getName());
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String name;
    private final String haUrl;
    private final String token;
    private final String mode;    // "state", "services", "call"
    private final String entity;  // entity_id or domain/service

    /** Read state of a specific entity. */
    public static HomeAssistantAction getState(String haUrl, String token, String entityId) {
        return new HomeAssistantAction("ha-state", haUrl, token, "state", entityId);
    }

    /** List available services. */
    public static HomeAssistantAction listServices(String haUrl, String token) {
        return new HomeAssistantAction("ha-services", haUrl, token, "services", "");
    }

    /** Call a service (write). */
    public static HomeAssistantAction callService(String haUrl, String token,
                                                   String domain, String service,
                                                   String entityId) {
        return new HomeAssistantAction("ha-call", haUrl, token,
                "call", domain + "/" + service + "/" + entityId);
    }

    private HomeAssistantAction(String name, String haUrl, String token,
                                 String mode, String entity) {
        this.name = name;
        this.haUrl = haUrl.replaceAll("/$", "");
        this.token = token;
        this.mode = mode;
        this.entity = entity;
    }

    @Override public String name() { return name; }
    @Override public String category() { return "call".equals(mode) ? "write" : "read"; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            HttpRequest request;
            switch (mode) {
                case "state" -> request = buildGet("/api/states/" + entity);
                case "services" -> request = buildGet("/api/services");
                case "call" -> {
                    String[] parts = entity.split("/", 3);
                    if (parts.length < 3) {
                        return ActionResult.fail(name,
                                "Invalid call format, need domain/service/entity_id", start);
                    }
                    String domain = parts[0];
                    String service = parts[1];
                    String targetEntity = parts[2];
                    String body = String.format("{\"entity_id\":\"%s\"}", targetEntity);
                    request = HttpRequest.newBuilder()
                            .uri(URI.create(haUrl + "/api/services/" + domain + "/" + service))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .timeout(Duration.ofSeconds(15))
                            .build();
                }
                default -> { return ActionResult.fail(name, "Unknown mode: " + mode, start); }
            }

            HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            long ms = Duration.between(start, Instant.now()).toMillis();

            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                String body = resp.body();
                if (body.length() > 500) body = body.substring(0, 497) + "...";
                LOG.info(() -> "HA " + mode + ": " + entity + " → " + resp.statusCode() + " (" + ms + "ms)");
                return ActionResult.ok(name, body, start);
            } else {
                LOG.warning(() -> "HA " + mode + " failed: " + resp.statusCode() + " " + resp.body());
                return ActionResult.fail(name, "HA " + resp.statusCode() + ": " + resp.body(), start);
            }
        } catch (Exception e) {
            LOG.warning("HA action failed: " + e.getMessage());
            return ActionResult.fail(name, e.getMessage(), start);
        }
    }

    private HttpRequest buildGet(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(haUrl + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
    }
}
