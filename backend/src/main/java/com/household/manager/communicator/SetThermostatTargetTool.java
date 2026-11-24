package com.household.manager.communicator;

import com.household.manager.ai.AgentScope;
import com.household.manager.ai.AgentTool;
import com.household.manager.thermostat.ThermostatDto;
import com.household.manager.thermostat.ThermostatService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Setzt den Sollwert eines Thermostats auf gesprochenen Wunsch ("stell das Wohnzimmer auf 21").
 * Schreibt ueber {@link ThermostatService} — gleiche Validierung und gleiches Audit wie das Tablet.
 */
@Component
@RequiredArgsConstructor
public class SetThermostatTargetTool implements AgentTool {

    private final ThermostatService thermostatService;

    @Override
    public String name() {
        return "set_thermostat_target";
    }

    @Override
    public String description() {
        return "Setzt die Solltemperatur eines Thermostats (5 bis 30 Grad in 0,5-Schritten; 5 = aus). "
                + "Die thermostatId steht im Hauszustand.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "thermostatId", Map.of("type", "integer"),
                        "targetTemp", Map.of("type", "number")),
                "required", List.of("thermostatId", "targetTemp"));
    }

    @Override
    public AgentScope scope() {
        return AgentScope.COMMUNICATOR;
    }

    @Override
    public String execute(Map<String, Object> input) {
        if (!(input.get("thermostatId") instanceof Number id) || !(input.get("targetTemp") instanceof Number target)) {
            throw new IllegalArgumentException("thermostatId und targetTemp muessen Zahlen sein");
        }
        ThermostatDto result = thermostatService.setTarget(id.longValue(), target.doubleValue());
        return result.name() + " ist auf " + target.doubleValue() + " Grad gestellt";
    }
}
