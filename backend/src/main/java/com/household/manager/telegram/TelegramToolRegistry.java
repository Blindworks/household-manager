package com.household.manager.telegram;

import com.household.manager.telegram.tools.AgentTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sammelt alle {@link AgentTool}-Beans ein und kapselt ihre Ausführung:
 * Fehler werden nie geworfen, sondern als Fehler-Result zurückgegeben,
 * damit das Modell sinnvoll darauf antworten kann.
 */
@Component
@Slf4j
public class TelegramToolRegistry {

    private final Map<String, AgentTool> tools;

    public TelegramToolRegistry(List<AgentTool> agentTools) {
        Map<String, AgentTool> byName = new LinkedHashMap<>();
        agentTools.forEach(tool -> byName.put(tool.name(), tool));
        this.tools = byName;
    }

    /** Tool-Definitionen im Format der Anthropic Messages API. */
    public List<Map<String, Object>> toolDefinitions() {
        return tools.values().stream()
                .map(tool -> Map.<String, Object>of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "input_schema", tool.inputSchema()))
                .toList();
    }

    public ToolResult execute(String name, Map<String, Object> input) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
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
