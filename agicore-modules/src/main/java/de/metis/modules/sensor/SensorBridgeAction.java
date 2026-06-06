package de.metis.modules.sensor;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Polls the S9 Sensor Bridge via WebSocket for the latest fused sensor data.
 * Connects to ws://localhost:8765/sensors, grabs one JSON frame, returns it.
 * Frames include: accel, gyro, mag, baro, lux, quat, roll, pitch, yaw, hr.
 * The bridge already runs the Madgwick AHRS filter so data arrives pre-fused.
 */
public class SensorBridgeAction implements Action {

    public static final String NAME = "sensor-bridge";
    private static final Logger LOG = Logger.getLogger(SensorBridgeAction.class.getName());
    private final String wsUrl;

    public SensorBridgeAction() { this("ws://localhost:8765/sensors"); }
    public SensorBridgeAction(String wsUrl) { this.wsUrl = wsUrl; }

    @Override public String name() { return NAME; }
    @Override public String category() { return "read"; }
    @Override public ApprovalLevel approvalLevel() { return ApprovalLevel.AUTO; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        CompletableFuture<String> result = new CompletableFuture<>();

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            WebSocket ws = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                        final StringBuilder buf = new StringBuilder();

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            buf.append(data);
                            if (last) { result.complete(buf.toString()); webSocket.sendClose(1000, "done"); }
                            return null;
                        }

                        @Override
                        public void onError(WebSocket webSocket, Throwable error) {
                            result.completeExceptionally(error);
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                            if (!result.isDone()) result.complete(buf.toString());
                            return null;
                        }
                    }).join();

            String json = result.get(5, TimeUnit.SECONDS);
            if (json == null || json.isEmpty())
                return ActionResult.fail(NAME, "No sensor data received", start);
            return ActionResult.ok(NAME, json, start);
        } catch (Exception e) {
            return ActionResult.fail(NAME, "Sensor bridge error: " + e.getMessage(), start);
        }
    }
}
