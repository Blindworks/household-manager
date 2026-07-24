package com.household.manager.telegram;

/** Ergebnis einer Tool-Ausführung für den tool_result-Block. */
public record ToolResult(String content, boolean error) {

    public static ToolResult ok(String content) {
        return new ToolResult(content, false);
    }

    public static ToolResult failure(String message) {
        return new ToolResult(message, true);
    }
}
