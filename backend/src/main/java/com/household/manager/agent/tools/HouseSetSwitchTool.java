package com.household.manager.agent.tools;

import com.household.manager.agent.ActionClass;
import com.household.manager.agent.AgentAction;
import com.household.manager.agent.AgentActionGate;
import com.household.manager.ai.AgentScope;
import com.household.manager.ai.AgentTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Schaltet einen Schalter über die Aktionsschleuse (Name mit Präfix, um den Telegram-Tools nicht zu gleichen). */
@Component
@RequiredArgsConstructor
public class HouseSetSwitchTool implements AgentTool {

    private final AgentActionGate gate;

    @Override
    public String name() {
        return "house_set_switch";
    }

    @Override
    public String description() {
        return "Schaltet einen Schalter ein oder aus. Je nach Freigabe-Richtlinie direkt oder als Vorschlag.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "entityId", Map.of("type", "string", "description", "Entity-Id des Schalters aus dem Snapshot"),
                        "state", Map.of("type", "string", "enum", List.of("on", "off")),
                        "reason", ActionInputs.REASON_PROPERTY),
                "required", List.of("entityId", "state", "reason"));
    }

    @Override
    public AgentScope scope() {
        return AgentScope.HOUSE;
    }

    @Override
    public String execute(Map<String, Object> input) {
        String reason = ActionInputs.reason(input);
        String entityId = ActionInputs.required(input, "entityId");
        String state = ActionInputs.required(input, "state");
        return gate.submit(new AgentAction(ActionClass.SWITCH, entityId, Map.of("state", state), reason, false))
                .messageForModel();
    }
}
