package de.metis.kernel.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Lightweight MCP (Model Context Protocol) client over stdio.
 * <p>
 * Connects to any MCP-compatible server process (e.g., Playwright MCP,
 * filesystem MCP, database MCP) via JSON-RPC 2.0 over stdin/stdout.
 * No Spring, no heavy SDK — just ProcessBuilder + Jackson.
 * <p>
 * Protocol flow:
 * <ol>
 *   <li>Spawn server process</li>
 *   <li>Send initialize → receive server capabilities</li>
 *   <li>Send tools/list → receive tool definitions</li>
 *   <li>Wrap each tool as a callable action via tools/call</li>
 * </ol>
 * <p>
 * Timeouts: initialize (15s), tool-list (10s), tool-call (30s default).
 */
public class McpClientService implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(McpClientService.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String PROTOCOL_VERSION = "2024-11-05";

    private final String name;
    private final List<String> command;
    private final Map<String, String> env;
    private final int initTimeoutSec;
    private final int toolCallTimeoutSec;

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private Thread readerThread;
    private final BlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();
    private final AtomicInteger requestId = new AtomicInteger(0);
    private volatile boolean initialized;
    private volatile boolean running;

    private String serverName;
    private String serverVersion;
    private List<McpTool> tools = List.of();

    public McpClientService(String name, List<String> command) {
        this(name, command, Map.of(), 15, 30);
    }

    public McpClientService(String name, List<String> command, Map<String, String> env,
                            int initTimeoutSec, int toolCallTimeoutSec) {
        this.name = name;
        this.command = List.copyOf(command);
        this.env = Map.copyOf(env);
        this.initTimeoutSec = initTimeoutSec;
        this.toolCallTimeoutSec = toolCallTimeoutSec;
    }

    // ── Connection ──

    /**
     * Connect to the MCP server and discover tools.
     * @return list of discovered tools
     */
    public synchronized List<McpTool> connect() throws IOException {
        if (initialized) return tools;

        LOG.info(() -> "MCP connecting: " + name + " cmd=" + command);

        var pb = new ProcessBuilder(command);
        pb.environment().putAll(env);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        process = pb.start();

        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        running = true;
        readerThread = new Thread(this::readLoop, "mcp-" + name + "-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        try {
            // 1. Initialize
            ObjectNode initReq = newRequest("initialize");
            initReq.putObject("params")
                    .put("protocolVersion", PROTOCOL_VERSION)
                    .putObject("capabilities");
            initReq.withObject("/params")
                    .putObject("clientInfo")
                    .put("name", "metis")
                    .put("version", "0.2.0");

            JsonNode initResp = sendRequest(initReq, initTimeoutSec);
            if (initResp.has("error")) {
                throw new IOException("MCP init error: " + initResp.get("error"));
            }

            JsonNode result = initResp.get("result");
            serverName = result.path("serverInfo").path("name").asText(name);
            serverVersion = result.path("serverInfo").path("version").asText("?");

            // 2. Notify initialized
            ObjectNode notified = newRequest("notifications/initialized");
            sendNotification(notified);

            // 3. Discover tools
            ObjectNode listReq = newRequest("tools/list");
            JsonNode toolsResp = sendRequest(listReq, initTimeoutSec);
            tools = parseTools(toolsResp);

            initialized = true;
            LOG.info(() -> "MCP " + name + " ready: " + tools.size() + " tools, "
                    + "server=" + serverName + " v" + serverVersion);
        } catch (Exception e) {
            close();
            throw new IOException("MCP connect failed: " + e.getMessage(), e);
        }

        return tools;
    }

    // ── Tool Invocation ──

    /**
     * Call a tool on the MCP server.
     */
    public String callTool(String toolName, Map<String, Object> arguments) throws IOException {
        return callTool(toolName, arguments, toolCallTimeoutSec);
    }

    /**
     * Call a tool with explicit timeout.
     */
    public String callTool(String toolName, Map<String, Object> arguments, int timeoutSec)
            throws IOException {
        if (!initialized) throw new IOException("MCP " + name + " not connected");

        ObjectNode req = newRequest("tools/call");
        ObjectNode params = req.putObject("params");
        params.put("name", toolName);
        params.set("arguments", JSON.valueToTree(arguments));

        JsonNode resp = sendRequest(req, timeoutSec);
        if (resp.has("error")) {
            throw new IOException("MCP tool error: " + resp.get("error"));
        }

        JsonNode content = resp.path("result").path("content");
        if (content.isArray() && content.size() > 0) {
            return content.get(0).path("text").asText("");
        }
        return resp.path("result").toString();
    }

    // ── JSON-RPC Helpers ──

    private ObjectNode newRequest(String method) {
        ObjectNode req = JSON.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", requestId.incrementAndGet());
        req.put("method", method);
        return req;
    }

    private void sendNotification(ObjectNode notification) throws IOException {
        notification.remove("id"); // notifications have no id
        String line = JSON.writeValueAsString(notification);
        synchronized (stdin) {
            stdin.write(line);
            stdin.newLine();
            stdin.flush();
        }
    }

    private JsonNode sendRequest(ObjectNode request, int timeoutSec) throws IOException {
        String line = JSON.writeValueAsString(request);
        synchronized (stdin) {
            stdin.write(line);
            stdin.newLine();
            stdin.flush();
        }

        try {
            String raw = responseQueue.poll(timeoutSec, TimeUnit.SECONDS);
            if (raw == null) {
                throw new IOException("MCP timeout after " + timeoutSec + "s: " + request.path("method"));
            }
            return JSON.readTree(raw);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("MCP interrupted: " + e.getMessage());
        }
    }

    private void readLoop() {
        try {
            String line;
            while (running && (line = stdout.readLine()) != null) {
                if (!line.isBlank()) {
                    responseQueue.offer(line);
                }
            }
        } catch (IOException e) {
            if (running) {
                LOG.fine(() -> "MCP " + name + " read error: " + e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<McpTool> parseTools(JsonNode response) {
        JsonNode toolsNode = response.path("result").path("tools");
        if (!toolsNode.isArray()) return List.of();

        var list = new ArrayList<McpTool>();
        for (JsonNode t : toolsNode) {
            String toolName = t.path("name").asText();
            String desc = t.path("description").asText("");
            JsonNode schema = t.path("inputSchema");
            Map<String, Object> inputSchema = schema.isObject()
                    ? JSON.convertValue(schema, Map.class) : Map.of();
            list.add(new McpTool(toolName, desc, inputSchema));
        }
        return List.copyOf(list);
    }

    // ── Accessors ──

    public String name() { return name; }
    public String serverName() { return serverName; }
    public String serverVersion() { return serverVersion; }
    public List<McpTool> tools() { return tools; }
    public boolean isConnected() { return initialized && running && process != null && process.isAlive(); }

    @Override
    public synchronized void close() {
        running = false;
        initialized = false;
        try {
            if (stdin != null) stdin.close();
        } catch (IOException ignored) {}
        try {
            if (stdout != null) stdout.close();
        } catch (IOException ignored) {}
        if (process != null) {
            process.destroyForcibly();
            try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
        LOG.info(() -> "MCP " + name + " disconnected");
    }

    // ── Data Types ──

    /**
     * A tool discovered on the MCP server.
     */
    public record McpTool(
            String name,
            String description,
            Map<String, Object> inputSchema
    ) {
        public Map<String, Object> toDict() {
            var m = new LinkedHashMap<String, Object>();
            m.put("name", name);
            m.put("description", description);
            m.put("inputSchema", inputSchema);
            return m;
        }
    }
}
