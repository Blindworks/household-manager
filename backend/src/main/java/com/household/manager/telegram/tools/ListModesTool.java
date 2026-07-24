package com.household.manager.telegram.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.HouseModeQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Haus-Modi (z. B. Abwesend, Nacht) mit Zustand. */
@Component
@RequiredArgsConstructor
public class ListModesTool implements AgentTool {

    private final HouseModeQueryService houseModeQueryService;
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String name() {
        return "list_modes";
    }

    @Override
    public String description() {
        return "Listet die Haus-Modi mit entityId, Name und Zustand (on/off). "
                + "Zuerst aufrufen, um die entityId fuer set_mode zu finden.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public String execute(Map<String, Object> input) throws Exception {
        return json.writeValueAsString(houseModeQueryService.listModes());
    }
}
