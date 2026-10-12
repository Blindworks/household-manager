package com.household.manager.agent;

/**
 * Ergebnis der Aktionsschleuse. {@code messageForModel} geht als Tool-Ergebnis zurück ans Modell,
 * {@code proposalId} ist null, wenn nichts angelegt wurde (z. B. wegen Duplikat oder Limit).
 */
public record GateResult(String messageForModel, Long proposalId, boolean executed) {
}
