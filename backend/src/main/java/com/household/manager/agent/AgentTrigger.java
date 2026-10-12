package com.household.manager.agent;

import java.util.List;

/**
 * Anlass eines Agentenlaufs samt Details (z. B. die Entitäten, die den Wecker ausgelöst haben).
 * {@link #describe()} ist die einzige Textform: Nutzernachricht an das Modell und Laufprotokoll
 * zeigen dadurch dasselbe.
 */
public record AgentTrigger(AgentTriggerType type, List<String> details) {

    public String describe() {
        return switch (type) {
            case SCHEDULED -> "Grundtakt";
            case WAKE -> "Wecker: " + String.join(", ", details);
            case MANUAL -> "Manuell";
        };
    }
}
