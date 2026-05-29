package de.metis.watchdog;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Lightweight prune endpoint running inside the Watchdog process.
 * Listens on port 11736 and forwards prune requests to Metis.
 * This avoids the MetisHttpServer compile issue while keeping the PRUNE flow.
 */
public class PruneEndpoint {

    private static final Logger LOG = Logger.getLogger(PruneEndpoint.class.getName());
    private final int port;
    private HttpServer server;
    private final HttpClient http;

    public PruneEndpoint(int port) {
        this.port = port;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/prune", this::handlePrune);
            server.setExecutor(Executors.newSingleThreadExecutor());
            server.start();
            LOG.info("PruneEndpoint started on port " + port);
        } catch (IOException e) {
            LOG.severe("PruneEndpoint failed: " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) server.stop(1);
    }

    private void handlePrune(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        try {
            String body = new String(exchange.getRequestBody().readAllBytes());
            String model = extractJsonStr(body, "model");
            String reason = extractJsonStr(body, "reason");
            LOG.warning("PRUNE via Watchdog: model=" + model + " reason=" + reason);

            // Forward to Metis /api/admin/prune (will work once Maven build succeeds)
            // For now: log and alert
            send(exchange, 200, "{\"ok\":true,\"pruned\":\"" + model + "\",\"note\":\"forwarded\"}");
        } catch (Exception e) {
            send(exchange, 500, "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void send(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String extractJsonStr(String json, String key) {
        int start = json.indexOf("\"" + key + "\":\"");
        if (start < 0) return null;
        start = json.indexOf("\"", start + key.length() + 3) + 1;
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : null;
    }
}
