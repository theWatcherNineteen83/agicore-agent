package de.metis.modules.events;

import de.metis.modules.events.EventTrigger;
import de.metis.modules.Agent;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * MQTT event listener — subscribes to Home Assistant topics and generates
 * Metis goals on state changes. Replaces REST polling with real-time push.
 */
public class MqttEventService implements EventTrigger, MqttCallback {

    private static final Logger LOG = Logger.getLogger(MqttEventService.class.getName());

    private final String brokerUrl;
    private final String clientId;
    private final String username;
    private final String password;
    private final List<String> topics;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, String> lastStates = new ConcurrentHashMap<>();

    private MqttClient client;
    private Agent agent;

    public MqttEventService(String brokerUrl, String username, String password,
                            List<String> topics) {
        this.brokerUrl = brokerUrl;
        this.clientId = "metis-mqtt-" + UUID.randomUUID().toString().substring(0, 8);
        this.username = username;
        this.password = password;
        this.topics = topics;
    }

    /** Quick constructor: no auth, wildcard topics. */
    public MqttEventService(String brokerUrl, List<String> topics) {
        this(brokerUrl, null, null, topics);
    }

    @Override
    public String name() { return "mqtt-events"; }

    @Override
    public String description() {
        return "MQTT subscriber: " + brokerUrl + " → " + topics.size() + " topics";
    }

    @Override
    public void start(Agent agent) {
        if (running.getAndSet(true)) return;
        this.agent = agent;

        try {
            client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setCleanSession(true);
            opts.setConnectionTimeout(10);
            opts.setKeepAliveInterval(30);
            opts.setAutomaticReconnect(true);
            if (username != null && !username.isBlank()) {
                opts.setUserName(username);
                opts.setPassword(password != null ? password.toCharArray() : new char[0]);
            }

            client.setCallback(this);
            client.connect(opts);

            // Subscribe to all requested topics
            for (String topic : topics) {
                client.subscribe(topic, 1);
                LOG.info("MQTT subscribed: " + topic);
            }

            LOG.info("MQTT connected: " + brokerUrl + " (" + topics.size() + " topics)");
        } catch (MqttException e) {
            LOG.warning("MQTT connection failed: " + e.getMessage());
            running.set(false);
        }
    }

    @Override
    public void stop() {
        running.set(false);
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
            }
        } catch (MqttException e) {
            LOG.fine("MQTT disconnect: " + e.getMessage());
        }
        LOG.info("MQTT service stopped");
    }

    // ── MqttCallback ───────────────────────────────────────────────

    @Override
    public void connectionLost(Throwable cause) {
        LOG.warning("MQTT connection lost: " + cause.getMessage());
        // Auto-reconnect is enabled, so this should recover
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        String payload = new String(message.getPayload(), java.nio.charset.StandardCharsets.UTF_8);
        String state = payload.trim();

        // Track state changes
        String oldState = lastStates.put(topic, state);
        if (oldState != null && oldState.equals(state)) {
            return; // No change
        }

        // Extract friendly name from topic
        String entityName = topicToName(topic);
        String eventDesc;
        if (oldState == null) {
            eventDesc = String.format("MQTT: %s = %s (initial)", entityName, state);
        } else {
            eventDesc = String.format("MQTT: %s wechselte von '%s' → '%s'",
                    entityName, oldState, state);
        }

        // Determine priority
        int priority = 55;
        if (topic.contains("motion") && "ON".equalsIgnoreCase(state)) priority = 85;
        if (topic.contains("door") || topic.contains("lock")) priority = 80;
        if (topic.contains("smoke") || topic.contains("gas") || topic.contains("alarm")) priority = 95;
        if (topic.contains("person") || topic.contains("presence")) priority = 70;

        agent.addGoal(eventDesc, "mqtt-event", priority, 0.75, 1);
        LOG.fine("MQTT → goal: " + eventDesc);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // Not used — we're only subscribing
    }

    // ── Helpers ────────────────────────────────────────────────────

    private String topicToName(String topic) {
        // Convert homeassistant/binary_sensor/motion_kitchen/state → "motion_kitchen"
        String[] parts = topic.split("/");
        if (parts.length >= 3) {
            return parts[parts.length - 2]; // entity name
        }
        return parts[parts.length - 1]; // last segment
    }
}
