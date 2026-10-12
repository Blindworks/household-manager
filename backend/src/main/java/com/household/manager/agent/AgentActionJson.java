package com.household.manager.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Einzige Serialisierung einer {@link AgentAction} in die Spalte {@code agent_proposal.action_json}.
 * Notifier und ProposalService lesen dieselbe Form; zwei eigene Varianten könnten auseinanderlaufen
 * und ein Vorschlag würde dann im Telegram-Text anders beschrieben als ausgeführt.
 */
public final class AgentActionJson {

    private AgentActionJson() {
    }

    public static String write(ObjectMapper objectMapper, AgentAction action) {
        try {
            return objectMapper.writeValueAsString(action);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Aktion nicht serialisierbar: " + ex.getOriginalMessage(), ex);
        }
    }

    public static AgentAction read(ObjectMapper objectMapper, String json) {
        try {
            return objectMapper.readValue(json, AgentAction.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Gespeicherte Aktion unlesbar: " + ex.getOriginalMessage(), ex);
        }
    }
}
