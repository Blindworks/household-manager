package com.household.manager.telegram.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.SwitchQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Listet alle schaltbaren Entitäten (Lichter, Steckdosen) mit Zustand. */
@Component
@RequiredArgsConstructor
public class ListSwitchesTool implements AgentTool {

    private final SwitchQueryService switchQueryService;
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String name() {
        return "list_switches";
    }

    @Override
    public String description() {
        return "Listet alle schaltbaren Geraete (Lichter, Steckdosen) mit entityId, "
                + "Name, Zustand (on/off), Verfuegbarkeit und aktueller Leistung in Watt. "
                + "Immer zuerst aufrufen, um die entityId fuer set_switch zu finden.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public String execute(Map<String, Object> input) throws Exception {
        List<Map<String, Object>> switches = switchQueryService.listSwitches(null).stream()
                .map(sw -> {
                    Map<String, Object> entry = new LinkedHashMap<String, Object>();
                    entry.put("entityId", sw.entityId());
                    entry.put("name", sw.displayName());
                    entry.put("state", sw.state());
                    entry.put("available", sw.available());
                    entry.put("powerWatts", sw.powerWatts());
                    return entry;
                })
                .toList();
        return json.writeValueAsString(switches);
    }
}
