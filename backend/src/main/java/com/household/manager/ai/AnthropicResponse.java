package com.household.manager.ai;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Antwort der Messages-API: stop_reason, rohe Content-Bloecke, Token-Zaehler (0, wenn unbekannt). */
public record AnthropicResponse(String stopReason, List<Map<String, Object>> content,
                                int inputTokens, int outputTokens) {

    public AnthropicResponse(String stopReason, List<Map<String, Object>> content) {
        this(stopReason, content, 0, 0);
    }

    public boolean wantsToolUse() {
        return "tool_use".equals(stopReason);
    }

    public List<Map<String, Object>> toolUseBlocks() {
        return content.stream().filter(block -> "tool_use".equals(block.get("type"))).toList();
    }

    public String text() {
        return content.stream()
                .filter(block -> "text".equals(block.get("type")))
                .map(block -> String.valueOf(block.get("text")))
                .collect(Collectors.joining("\n"))
                .trim();
    }
}
