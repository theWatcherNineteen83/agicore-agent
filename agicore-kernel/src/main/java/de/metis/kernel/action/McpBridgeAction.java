package de.metis.kernel.action;

import de.metis.kernel.mcp.McpClientService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Bridges MCP (Model Context Protocol) tools into Metis as native Actions.
 * <p>
 * Connects to an MCP server via stdio, discovers its tools, and wraps
 * each tool for the Metis action executor. This is the strategic integration
 * point: any MCP-compatible tool server (Playwright, filesystem, database,
 * Slack, etc.) becomes a Metis Action without custom code.
 * <p>
 * Category: read (by default, override per-tool). Approval: AUTO.
 * <p>
 * Configuration:
 * <ul>
 *   <li>Constructor takes MCP server command + env</li>
 *   <li>At execute(), connects and returns tool list</li>
 *   <li>Individual tools are called via {@link McpToolInvocationAction}</li>
 * </ul>
 */
public class McpBridgeAction implements Action {

    private static final Logger LOG = Logger.getLogger(McpBridgeAction.class.getName());
    public static final String NAME = "mcp-bridge";

    private final String serverName;
    private final List<String> command;
    private final Map<String, String> env;
    private McpClientService client;

    public McpBridgeAction(String serverName, List<String> command) {
        this(serverName, command, Map.of());
    }

    public McpBridgeAction(String serverName, List<String> command, Map<String, String> env) {
        this.serverName = serverName;
        this.command = command;
        this.env = env;
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "bridge"; }
    @Override public ApprovalLevel approvalLevel() { return ApprovalLevel.AUTO; }

    @Override
    public ActionResult execute() {
        var now = Instant.now();
        try {
            client = new McpClientService(serverName, command, env, 15, 30);
            var tools = client.connect();

            var sb = new StringBuilder();
            sb.append("MCP Server: ").append(client.serverName())
                    .append(" v").append(client.serverVersion()).append("\n");
            sb.append(tools.size()).append(" tools discovered:\n");
            for (var tool : tools) {
                sb.append("  - ").append(tool.name())
                        .append(": ").append(tool.description()).append("\n");
            }

            LOG.info(() -> "MCP bridge: " + serverName + " — " + tools.size() + " tools");
            return ActionResult.ok(NAME, sb.toString(), now);
        } catch (Exception e) {
            LOG.warning("MCP bridge failed: " + e.getMessage());
            return ActionResult.fail(NAME, "MCP connect: " + e.getMessage(), now);
        }
    }

    public McpClientService client() { return client; }
}
