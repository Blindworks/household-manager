package com.household.manager.telegram;

import com.household.manager.telegram.tools.AgentTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TelegramToolRegistryTest {

    private AgentTool tool(String name, String result) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public String execute(Map<String, Object> input) {
                if (result == null) {
                    throw new IllegalStateException("kaputt");
                }
                return result;
            }
        };
    }

    @Test
    void buildsAnthropicToolDefinitions() {
        TelegramToolRegistry registry = new TelegramToolRegistry(List.of(tool("a", "ok")));

        List<Map<String, Object>> defs = registry.toolDefinitions();

        assertEquals(1, defs.size());
        assertEquals("a", defs.get(0).get("name"));
        assertEquals("test", defs.get(0).get("description"));
        assertNotNull(defs.get(0).get("input_schema"));
    }

    @Test
    void executeReturnsToolOutput() {
        TelegramToolRegistry registry = new TelegramToolRegistry(List.of(tool("a", "ergebnis")));

        ToolResult result = registry.execute("a", Map.of());

        assertFalse(result.error());
        assertEquals("ergebnis", result.content());
    }

    @Test
    void unknownToolIsAnErrorResultNotAnException() {
        TelegramToolRegistry registry = new TelegramToolRegistry(List.of());

        ToolResult result = registry.execute("gibts_nicht", Map.of());

        assertTrue(result.error());
        assertTrue(result.content().contains("gibts_nicht"));
    }

    @Test
    void toolExceptionIsAnErrorResultNotAnException() {
        TelegramToolRegistry registry = new TelegramToolRegistry(List.of(tool("a", null)));

        ToolResult result = registry.execute("a", Map.of());

        assertTrue(result.error());
        assertTrue(result.content().contains("kaputt"));
    }
}
