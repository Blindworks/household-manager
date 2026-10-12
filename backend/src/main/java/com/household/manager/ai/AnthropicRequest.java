package com.household.manager.ai;

import java.util.List;
import java.util.Map;

/** Ein Aufruf der Messages-API. {@code system} sind Content-Bloecke (Prompt-Caching moeglich). */
public record AnthropicRequest(String model, int maxTokens, List<Map<String, Object>> system,
                               List<AnthropicMessage> messages, List<Map<String, Object>> tools) {

    public static AnthropicRequest of(String model, int maxTokens, String systemText,
                                      List<AnthropicMessage> messages, List<Map<String, Object>> tools) {
        return new AnthropicRequest(model, maxTokens,
                List.of(Map.of("type", "text", "text", systemText)), messages, tools);
    }

    /** Textblock, den die API fuer Folgeaufrufe zwischenspeichert (nur fuer den statischen Prompt-Teil). */
    public static Map<String, Object> cachedBlock(String text) {
        return Map.of("type", "text", "text", text, "cache_control", Map.of("type", "ephemeral"));
    }

    public static Map<String, Object> block(String text) {
        return Map.of("type", "text", "text", text);
    }
}
