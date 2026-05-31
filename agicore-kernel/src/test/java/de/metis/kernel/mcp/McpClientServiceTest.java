package de.metis.kernel.mcp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

/**
 * Tests for McpClientService — lightweight MCP client.
 */
class McpClientServiceTest {

    @Test
    void serviceCreationWithDefaults() {
        var service = new McpClientService("test", List.of("echo", "hello"));
        assertEquals("test", service.name());
        assertFalse(service.isConnected());
        assertTrue(service.tools().isEmpty());
    }

    @Test
    void mcpToolToDict() {
        var tool = new McpClientService.McpTool(
                "read_file",
                "Read a file from the filesystem",
                Map.of("type", "object", "properties", Map.of(
                        "path", Map.<String,Object>of("type", "string", "description", "File path")
                ))
        );
        var dict = tool.toDict();
        assertEquals("read_file", dict.get("name"));
        assertEquals("Read a file from the filesystem", dict.get("description"));
        assertTrue(dict.containsKey("inputSchema"));
    }

    @Test
    void mcpToolRecordEquality() {
        var schema = Map.<String,Object>of("type", "object");
        var t1 = new McpClientService.McpTool("read", "desc", schema);
        var t2 = new McpClientService.McpTool("read", "desc", schema);
        assertEquals(t1, t2);
        assertEquals(t1.hashCode(), t2.hashCode());
    }

    @Test
    void closeWithoutConnectDoesNotThrow() {
        var service = new McpClientService("test", List.of("false"));
        assertDoesNotThrow(service::close);
    }

    @Test
    void invalidCommandFailsOnConnect() {
        var service = new McpClientService("invalid",
                List.of("/nonexistent/binary/xyzzy_nope_12345"));
        assertThrows(Exception.class, service::connect);
    }

    @Test
    void echoServerDiscoversNoTools() {
        // echo exits immediately, no MCP handshake
        var service = new McpClientService("echo", List.of("echo", "test"), Map.of(), 2, 5);
        // This will fail because echo doesn't speak MCP
        assertThrows(Exception.class, service::connect);
    }
}
