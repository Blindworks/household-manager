package com.household.manager.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentToolRegistryTest {

    private AgentTool tool(String name, AgentScope scope, String result) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "test"; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public AgentScope scope() { return scope; }
            @Override public String execute(Map<String, Object> input) { return result; }
        };
    }

    @Test
    void definitionsAreFilteredByScope() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                tool("tg", AgentScope.TELEGRAM, "a"), tool("house", AgentScope.HOUSE, "b")));

        List<Map<String, Object>> defs = registry.toolDefinitions(AgentScope.HOUSE);

        assertEquals(1, defs.size());
        assertEquals("house", defs.get(0).get("name"));
    }

    @Test
    void executeRefusesToolOfOtherScope() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool("tg", AgentScope.TELEGRAM, "a")));

        ToolResult result = registry.execute(AgentScope.HOUSE, "tg", Map.of());

        assertTrue(result.error());
        assertTrue(result.content().contains("Unbekanntes Tool"));
    }

    @Test
    void executeRunsToolOfMatchingScope() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool("tg", AgentScope.TELEGRAM, "ok")));

        ToolResult result = registry.execute(AgentScope.TELEGRAM, "tg", Map.of());

        assertFalse(result.error());
        assertEquals("ok", result.content());
    }

    @Test
    void executeConvertsExceptionIntoFailure() {
        AgentTool failing = new AgentTool() {
            @Override public String name() { return "boom"; }
            @Override public String description() { return "t"; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public String execute(Map<String, Object> input) { throw new IllegalStateException("kaputt"); }
        };
        AgentToolRegistry registry = new AgentToolRegistry(List.of(failing));

        ToolResult result = registry.execute(AgentScope.TELEGRAM, "boom", null);

        assertTrue(result.error());
        assertTrue(result.content().contains("kaputt"));
    }
}
