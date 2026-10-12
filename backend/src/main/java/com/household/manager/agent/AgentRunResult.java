package com.household.manager.agent;

/** Ergebnis eines Agentenlaufs; bei {@code success=false} steht der Grund in {@code error}. */
public record AgentRunResult(boolean success, String summary, String error,
                             int inputTokens, int outputTokens, int toolCalls, long durationMs) {
}
