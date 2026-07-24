package com.household.manager.telegram.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.PowerConsumerQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Aktuelle Stromverbraucher, größte zuerst. */
@Component
@RequiredArgsConstructor
public class ListPowerConsumersTool implements AgentTool {

    private final PowerConsumerQueryService powerConsumerQueryService;
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String name() {
        return "list_power_consumers";
    }

    @Override
    public String description() {
        return "Listet die aktuellen Stromverbraucher mit Leistung in Watt, "
                + "groesster Verbraucher zuerst.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public String execute(Map<String, Object> input) throws Exception {
        return json.writeValueAsString(powerConsumerQueryService.listConsumers(null));
    }
}
