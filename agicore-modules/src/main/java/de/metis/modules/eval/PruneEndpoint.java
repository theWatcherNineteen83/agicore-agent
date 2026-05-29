package de.metis.modules.eval;

import de.metis.modules.evolution.ModelRegistry;
import com.sun.net.httpserver.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.logging.Logger;

/**
 * Standalone HTTP endpoint for Watchdog-driven model pruning.
 * <p>
 * Runs on a separate port (11736) from the main Metis API (11735).
 * Only accepts POST /prune with {"model":"...","reason":"..."}
 * <p>
 * This avoids the need to recompile MetisHttpServer (which has
 * dependency on kernel classes not in the current JAR).
 */
public class PruneEndpoint {

    private static final Logger LOG = Logger.getLogger(PruneEndpoint.class.getName());

    private final ModelRegistry modelRegistry;
    private final int port;
    private HttpServer server;

    public PruneEndpoint(ModelRegistry modelRegistry, int port) {
        this.modelRegistry = modelRegistry;
        this.port = port;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/prune", this::handlePrune);
            server.createContext("/health", this::handleHealth);
            server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor());
            server.start();
            LOG.info("PruneEndpoint started on port " + port);
        } catch (IOException e) {
            LOG.severe("PruneEndpoint failed to start: " + e.getMessage());
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
            String model = extractJsonString(body, "model");
            String reason = extractJsonString(body, "reason");
            LOG.warning("PRUNE request: model=" + model + " reason=" + reason);

            if (modelRegistry != null && model != null && !model.isBlank()) {
                modelRegistry.pruneModel(model);
                send(exchange, 200, "{\"ok\":true,\"pruned\":\"" + model + "\"}");
                LOG.info("PRUNE executed: " + model);
            } else {
                send(exchange, 400, "{\"ok\":false,\"error\":\"Invalid model\"}");
            }
        } catch (Exception e) {
            send(exchange, 500, "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        send(exchange, 200, "{\"status\":\"ok\",\"endpoint\":\"prune\"}");
    }

    private void send(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String extractJsonString(String json, String key) {
        int start = json.indexOf("\"" + key + "\":\"");
        if (start < 0) return null;
        start = json.indexOf("\"", start + key.length() + 3) + 1;
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : null;
    }
}
