package de.metis.modules.events;

import de.metis.modules.Agent;
import de.metis.modules.action.CameraSnapshotAction;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Periodically captures snapshots from configured cameras and generates beliefs.
 * <p>
 * For each camera, captures a snapshot every {@code POLL_INTERVAL},
 * compares file sizes to detect motion/changes, and injects beliefs
 * into the WorldModel. Notable events (significant size change)
 * generate Metis goals for further investigation.
 */
public class CameraPollingTrigger implements EventTrigger {

    private static final Logger LOG = Logger.getLogger(CameraPollingTrigger.class.getName());
    private static final Duration POLL_INTERVAL = Duration.ofMinutes(5);
    private static final double SIGNIFICANT_CHANGE_RATIO = 2.0;

    private final List<CameraConfig> cameras;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollingThread;

    private final Map<String, Long> lastSizes = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastPollTimes = new ConcurrentHashMap<>();

    public CameraPollingTrigger(List<CameraConfig> cameras) {
        this.cameras = List.copyOf(cameras);
    }

    @Override
    public String name() { return "camera-polling"; }

    @Override
    public String description() {
        return "Captures snapshots from " + cameras.size() + " camera(s) every "
                + POLL_INTERVAL.toMinutes() + "min, detects changes";
    }

    @Override
    public void start(Agent agent) {
        if (running.getAndSet(true)) return;

        pollingThread = Thread.ofVirtual().name("camera-poll").start(() -> {
            while (running.get()) {
                try {
                    pollAndEvaluate(agent);
                    Thread.sleep(POLL_INTERVAL.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        LOG.info("Camera polling started — " + cameras.size() + " camera(s) every "
                + POLL_INTERVAL.toMinutes() + "min");
    }

    @Override
    public void stop() {
        running.set(false);
        if (pollingThread != null) pollingThread.interrupt();
        LOG.info("Camera polling stopped");
    }

    private void pollAndEvaluate(Agent agent) {
        for (var cfg : cameras) {
            try {
                evaluateCamera(agent, cfg);
            } catch (Exception e) {
                LOG.warning(() -> "Camera poll failed for " + cfg.name() + ": " + e.getMessage());
            }
        }
    }

    private void evaluateCamera(Agent agent, CameraConfig config) {
        var action = new CameraSnapshotAction(config.name(), config.source());
        var result = action.execute();
        var now = Instant.now();

        if (!result.success()) {
            agent.worldModel().update(
                    "camera " + config.name() + " is offline (" + result.error() + ")",
                    0.30, "camera-poll", false);
            lastPollTimes.put(config.name(), now);
            return;
        }

        // Camera working
        agent.worldModel().update(
                "camera " + config.name() + " is online and capturing",
                0.90, "camera-poll", true);

        long currentSize = extractFileSize(result.body());
        Long previousSize = lastSizes.put(config.name(), currentSize);
        lastPollTimes.put(config.name(), now);

        // Store snapshot info as belief
        agent.worldModel().update(
                "last snapshot from " + config.name() + " is " + currentSize + " bytes",
                0.95, "camera-poll", true);

        // Detect significant change (motion / scene change)
        if (previousSize != null && previousSize > 0 && currentSize > 0) {
            double ratio = (double) currentSize / previousSize;
            if (ratio > SIGNIFICANT_CHANGE_RATIO || ratio < 1.0 / SIGNIFICANT_CHANGE_RATIO) {
                agent.worldModel().update(
                        "motion detected on " + config.name()
                                + " (size changed " + String.format("%.1f", ratio) + "x, "
                                + previousSize + " → " + currentSize + " bytes)",
                        0.70, "camera-poll", true);

                // Generate goal for the planner
                agent.addGoal(
                        "Investigate motion on " + config.name()
                                + " (" + String.format("%.1f", ratio) + "x change)",
                        "observation",
                        7, 3.0, 2);

                LOG.info(() -> "Motion detected: " + config.name()
                        + " (" + previousSize + " → " + currentSize + " bytes, "
                        + String.format("%.1f", ratio) + "x)");
            }
        }

        LOG.fine(() -> "Camera snapshot: " + config.name() + " (" + currentSize + " bytes)");
    }

    /** Extract file size from JSON result body. */
    private static long extractFileSize(String body) {
        if (body == null) return 0;
        try {
            var idx = body.indexOf("\"size_bytes\":");
            if (idx < 0) return 0;
            var start = idx + 13;
            var end = body.indexOf(",", start);
            if (end < 0) end = body.indexOf("}", start);
            return Long.parseLong(body.substring(start, end).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Camera configuration for polling.
     */
    public record CameraConfig(String name, String source, String description) {
        public CameraConfig(String name, String source) {
            this(name, source, name);
        }
    }
}
