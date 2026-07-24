package com.household.manager.telegram;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Antwort der Messages-API: stop_reason plus rohe Content-Blöcke. */
public record AnthropicResponse(String stopReason, List<Map<String, Object>> content) {

    public boolean wantsToolUse() {
        return "tool_use".equals(stopReason);
    }

    public List<Map<String, Object>> toolUseBlocks() {
        return content.stream().filter(block -> "tool_use".equals(block.get("type"))).toList();
    }

    /** Alle Textblöcke zusammengefügt (die finale Antwort an den Nutzer). */
    public String text() {
        return content.stream()
                .filter(block -> "text".equals(block.get("type")))
                .map(block -> String.valueOf(block.get("text")))
                .collect(Collectors.joining("\n"))
                .trim();
    }
}
