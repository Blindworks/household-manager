package com.household.manager.telegram.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.nuki.NukiLockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Zustand der Nuki-Schlösser (verriegelt? Tür offen? Batterie?). */
@Component
@RequiredArgsConstructor
public class GetLockStatusTool implements AgentTool {

    private final NukiLockService nukiLockService;
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String name() {
        return "get_lock_status";
    }

    @Override
    public String description() {
        return "Zustand der Tuerschloesser: verriegelt/entriegelt, Tuersensor, Batterie.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public String execute(Map<String, Object> input) throws Exception {
        return json.writeValueAsString(nukiLockService.listLocks());
    }
}
