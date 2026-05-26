package de.metis.modules.events;

import de.metis.modules.events.EventTrigger;
import de.metis.modules.Agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Polls Home Assistant REST API for sensor state changes and generates Metis goals.
 * <p>
 * Watches for:
 * <ul>
 *   <li>Motion detection (binary_sensor.*_motion)</li>
 *   <li>Door/window sensors</li>
 *   <li>Camera events</li>
 *   <li>Person presence changes (device_tracker)</li>
 * </ul>
 */
public class HAEventPoller implements EventTrigger {

    private static final Logger LOG = Logger.getLogger(HAEventPoller.class.getName());

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(30);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final String haUrl;
    private final String haToken;
    private final HttpClient http;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollingThread;

    // Last known states for change detection
    private final Map<String, String> lastStates = new HashMap<>();
    private final Set<String> watchedSensors = new HashSet<>();

    public HAEventPoller(String haUrl, String haToken) {
        this.haUrl = haUrl.endsWith("/") ? haUrl.substring(0, haUrl.length() - 1) : haUrl;
        this.haToken = haToken;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Add entity IDs to watch. Supports wildcard prefixes like "binary_sensor.".
     */
    public HAEventPoller watch(String... entityIds) {
        Collections.addAll(watchedSensors, entityIds);
        return this;
    }

    @Override
    public String name() { return "ha-event-poller"; }

    @Override
    public String description() {
        return "Polls HA API (" + haUrl + ") every " + POLL_INTERVAL.getSeconds() 
                + "s for " + watchedSensors.size() + " watched sensors";
    }

    @Override
    public void start(Agent agent) {
        if (running.getAndSet(true)) return;

        pollingThread = Thread.ofVirtual().name("ha-event-poll").start(() -> {
            // Initial snapshot — populate baseline states
            fetchAndSnapshot();

            while (running.get()) {
                try {
                    Thread.sleep(POLL_INTERVAL.toMillis());
                    if (!running.get()) break;
                    pollForChanges(agent);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        LOG.info("HA Event polling started — " + watchedSensors.size() + " sensors");
    }

    @Override
    public void stop() {
        running.set(false);
        if (pollingThread != null) pollingThread.interrupt();
        LOG.info("HA Event polling stopped");
    }

    /** Initial fetch to establish baseline — no goals generated. */
    private void fetchAndSnapshot() {
        try {
            var states = fetchAllStates();
            if (states != null) {
                for (var entry : states.entrySet()) {
                    if (matchesWatched(entry.getKey())) {
                        lastStates.put(entry.getKey(), entry.getValue());
                    }
                }
                LOG.fine("HA baseline: " + lastStates.size() + " sensors tracked");
            }
        } catch (Exception e) {
            LOG.warning("HA baseline fetch failed: " + e.getMessage());
        }
    }

    /** Poll HA and generate goals for changed sensors. */
    private void pollForChanges(Agent agent) {
        try {
            var states = fetchAllStates();
            if (states == null) return;

            for (var entry : states.entrySet()) {
                String entityId = entry.getKey();
                String newState = entry.getValue();

                if (!matchesWatched(entityId)) continue;

                String oldState = lastStates.get(entityId);
                if (oldState != null && !oldState.equals(newState)) {
                    generateChangeGoal(agent, entityId, oldState, newState);
                }

                lastStates.put(entityId, newState);
            }
        } catch (Exception e) {
            LOG.warning("HA poll error: " + e.getMessage());
        }
    }

    /** Fetch all HA states via REST API. */
    private Map<String, String> fetchAllStates() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(haUrl + "/api/states"))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + haToken)
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            LOG.warning("HA API returned " + resp.statusCode());
            return null;
        }

        return parseStates(resp.body());
    }

    /** Parse HA /api/states JSON into entity_id → state map. */
    private Map<String, String> parseStates(String json) {
        Map<String, String> result = new HashMap<>();
        int arrStart = json.indexOf('[');
        if (arrStart < 0) return result;

        int pos = arrStart + 1;
        int arrEnd = json.lastIndexOf(']');

        while (pos < arrEnd) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0 || objStart >= arrEnd) break;
            int objEnd = findMatchingBrace(json, objStart);
            if (objEnd < 0) break;

            String obj = json.substring(objStart, objEnd + 1);
            String eid = extractJsonString(obj, "entity_id");
            String state = extractJsonString(obj, "state");

            // Extract friendly name for logging
            String friendly = extractFriendlyName(obj);

            if (eid != null && state != null) {
                result.put(eid, state);
                // Also store friendly name for goal generation
                if (friendly != null) {
                    result.put(eid + "._friendly", friendly);
                }
            }

            pos = objEnd + 1;
        }

        return result;
    }

    /** Extract friendly_name from attributes. */
    private String extractFriendlyName(String obj) {
        int attrStart = obj.indexOf("\"attributes\":{");
        if (attrStart < 0) return null;
        return extractJsonString(obj.substring(attrStart), "friendly_name");
    }

    /** Check if entity matches our watched list. */
    private boolean matchesWatched(String entityId) {
        for (String pattern : watchedSensors) {
            if (entityId.equals(pattern)) return true;
            if (pattern.endsWith(".") && entityId.startsWith(pattern)) return true;
        }
        return false;
    }

    /** Generate a Metis goal for a state change. */
    private void generateChangeGoal(Agent agent, String entityId, 
                                     String oldState, String newState) {
        String friendly = lastStates.get(entityId + "._friendly");
        String displayName = friendly != null ? friendly : entityId;

        String goal = String.format("HA-Event: %s wechselte von '%s' → '%s'",
                displayName, oldState, newState);

        // Determine priority based on sensor type
        int priority = 60;
        if (entityId.contains("motion") && "on".equals(newState)) priority = 85;
        if (entityId.contains("door")) priority = 80;
        if (entityId.contains("smoke") || entityId.contains("gas")) priority = 95;
        if (entityId.contains("person")) priority = 70;

        agent.addGoal(goal, "ha-event", priority, 0.75, 1);
        LOG.info("HA event → goal: " + goal);
    }

    // ── JSON helpers ──────────────────────────────────────────────

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) {
            search = "\"" + key + "\": \"";
            start = json.indexOf(search);
        }
        if (start < 0) return null;
        start += search.length();

        StringBuilder val = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                val.append(json.charAt(++i));
            } else if (c == '"') {
                break;
            } else {
                val.append(c);
            }
        }
        return val.toString();
    }

    private int findMatchingBrace(String json, int openBrace) {
        int depth = 0;
        boolean inString = false;
        for (int i = openBrace; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) inString = !inString;
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }
}
