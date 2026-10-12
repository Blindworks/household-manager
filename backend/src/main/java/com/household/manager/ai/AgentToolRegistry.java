package com.household.manager.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sammelt alle {@link AgentTool}-Beans ein und kapselt ihre Ausfuehrung je Scope:
 * Fehler werden nie geworfen, sondern als Fehler-Result zurueckgegeben, damit das
 * Modell sinnvoll darauf antworten kann. Ein Tool des falschen Scopes gilt als unbekannt.
 */
@Component
@Slf4j
public class AgentToolRegistry {

    private final Map<String, AgentTool> tools;

    public AgentToolRegistry(List<AgentTool> agentTools) {
        Map<String, AgentTool> byName = new LinkedHashMap<>();
        agentTools.forEach(tool -> byName.put(tool.name(), tool));
        this.tools = byName;
    }

    public List<Map<String, Object>> toolDefinitions(AgentScope scope) {
        return tools.values().stream()
                .filter(tool -> tool.scope() == scope)
                .map(tool -> Map.<String, Object>of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "input_schema", tool.inputSchema()))
                .toList();
    }

    public ToolResult execute(AgentScope scope, String name, Map<String, Object> input) {
        AgentTool tool = tools.get(name);
        if (tool == null || tool.scope() != scope) {
            return ToolResult.failure("Unbekanntes Tool: " + name);
        }
        try {
            return ToolResult.ok(tool.execute(input != null ? input : Map.of()));
        } catch (Exception ex) {
            log.warn("Tool {} fehlgeschlagen: {}", name, ex.getMessage());
            return ToolResult.failure("Tool-Fehler: " + ex.getMessage());
        }
    }
}
