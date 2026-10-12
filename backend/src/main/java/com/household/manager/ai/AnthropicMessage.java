package com.household.manager.ai;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Eine Nachricht im Anthropic-Messages-Format. Content-Blöcke bleiben bewusst
 * rohe Maps (text / tool_use / tool_result), damit kein polymorphes
 * Jackson-Mapping nötig ist.
 */
public record AnthropicMessage(String role, List<Map<String, Object>> content) {

    public static AnthropicMessage user(String text) {
        return new AnthropicMessage("user", List.of(Map.of("type", "text", "text", text)));
    }

    public static AnthropicMessage assistant(List<Map<String, Object>> content) {
        return new AnthropicMessage("assistant", content);
    }

    /**
     * Ein tool_result-Block; {@code is_error} steht nur bei Fehlern im Block. Gemeinsame Definition
     * für Telegram- und Hausverstand-Schleife, damit beide dasselbe Format an die API senden.
     */
    public static Map<String, Object> toolResult(String toolUseId, ToolResult result) {
        Map<String, Object> block = new HashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId);
        block.put("content", result.content());
        if (result.error()) {
            block.put("is_error", true);
        }
        return block;
    }

    public static AnthropicMessage toolResults(List<Map<String, Object>> results) {
        return new AnthropicMessage("user", results);
    }
}
