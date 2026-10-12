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

/** Schaltet einen Haus-Modus (z. B. Nachtmodus) über die Aktionsschleuse. */
@Component
@RequiredArgsConstructor
public class HouseSetModeTool implements AgentTool {

    private final AgentActionGate gate;

    @Override
    public String name() {
        return "house_set_mode";
    }

    @Override
    public String description() {
        return "Schaltet einen Haus-Modus ein oder aus. Je nach Freigabe-Richtlinie direkt oder als Vorschlag.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "entityId", Map.of("type", "string", "description", "Entity-Id des Modus aus dem Snapshot"),
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
        return gate.submit(new AgentAction(ActionClass.MODE, entityId, Map.of("state", state), reason, false))
                .messageForModel();
    }
}
