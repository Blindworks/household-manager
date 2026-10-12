package com.household.manager.agent.tools;

import com.household.manager.agent.ActionClass;
import com.household.manager.agent.AgentAction;
import com.household.manager.agent.AgentActionGate;
import com.household.manager.ai.AgentScope;
import com.household.manager.ai.AgentTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Heizung stellen. Geht immer durch die Schleuse, die den Korridor 5–24 °C erzwingt; nur die
 * tatsächlich übergebenen Parameter landen in der Aktion, damit ein fehlender Wert nicht als 0 gilt.
 */
@Component
@RequiredArgsConstructor
public class SetHeatingTool implements AgentTool {

    private final AgentActionGate gate;

    @Override
    public String name() {
        return "set_heating";
    }

    @Override
    public String description() {
        return "Stellt ein Thermostat: Zieltemperatur (5-24 Grad) und/oder Modus. "
                + "Je nach Freigabe-Richtlinie wird direkt gesetzt oder ein Vorschlag gestellt.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "thermostatId", Map.of("type", "integer", "description", "Id des Thermostats aus dem Snapshot"),
                        "targetTemp", Map.of("type", "number", "minimum", 5, "maximum", 24),
                        "mode", Map.of("type", "string", "enum", List.of("MANUAL", "OFF", "FROST_PROTECTION")),
                        "reason", ActionInputs.REASON_PROPERTY),
                "required", List.of("thermostatId", "reason"));
    }

    @Override
    public AgentScope scope() {
        return AgentScope.HOUSE;
    }

    @Override
    public String execute(Map<String, Object> input) {
        String reason = ActionInputs.reason(input);
        long thermostatId = NoteInputs.integer(input, "thermostatId");
        Map<String, Object> params = new HashMap<>();
        if (input.get("targetTemp") != null) {
            params.put("targetTemp", toDouble(input.get("targetTemp")));
        }
        if (input.get("mode") != null) {
            params.put("mode", String.valueOf(input.get("mode")).trim());
        }
        if (params.isEmpty()) {
            throw new IllegalArgumentException("targetTemp oder mode angeben.");
        }
        return gate.submit(new AgentAction(ActionClass.HEATING, String.valueOf(thermostatId), params, reason, false))
                .messageForModel();
    }

    private static Double toDouble(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("targetTemp muss eine Zahl sein: " + raw);
        }
    }
}
