package com.household.manager.telegram;

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

    public static AnthropicMessage toolResults(List<Map<String, Object>> results) {
        return new AnthropicMessage("user", results);
    }
}
