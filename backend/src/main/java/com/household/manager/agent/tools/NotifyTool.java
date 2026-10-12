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

/**
 * Benachrichtigung an die Bewohner. {@code urgent} lässt sie die Ruhezeit durchbrechen; das Modell
 * soll es nur bei echter Gefahr setzen (steht in der Beschreibung).
 */
@Component
@RequiredArgsConstructor
public class NotifyTool implements AgentTool {

    private final AgentActionGate gate;

    @Override
    public String name() {
        return "notify";
    }

    @Override
    public String description() {
        return "Schickt den Bewohnern eine Nachricht (telegram oder push). "
                + "urgent nur bei echter Gefahr setzen — dringende Nachrichten ignorieren die Ruhezeit.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "channel", Map.of("type", "string", "enum", List.of("telegram", "push")),
                        "message", Map.of("type", "string", "maxLength", 500),
                        "urgent", Map.of("type", "boolean", "description", "Nur bei echter Gefahr"),
                        "reason", ActionInputs.REASON_PROPERTY),
                "required", List.of("channel", "message", "reason"));
    }

    @Override
    public AgentScope scope() {
        return AgentScope.HOUSE;
    }

    @Override
    public String execute(Map<String, Object> input) {
        String reason = ActionInputs.reason(input);
        String channel = ActionInputs.required(input, "channel");
        String message = ActionInputs.required(input, "message");
        boolean urgent = Boolean.parseBoolean(String.valueOf(input.get("urgent")));
        return gate.submit(new AgentAction(ActionClass.NOTIFY, channel,
                Map.of("channel", channel, "message", message), reason, urgent)).messageForModel();
    }
}
