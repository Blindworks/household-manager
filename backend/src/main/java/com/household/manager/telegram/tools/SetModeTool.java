package com.household.manager.telegram.tools;

import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.entitystate.ManualEntityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Setzt einen Haus-Modus. Toggelt nur entityIds, die die Modus-Abfrage kennt —
 * sonst koennte das Modell beliebige input_booleans schalten.
 */
@Component
@RequiredArgsConstructor
public class SetModeTool implements AgentTool {

    private final HouseModeQueryService houseModeQueryService;
    private final ManualEntityService manualEntityService;

    @Override
    public String name() {
        return "set_mode";
    }

    @Override
    public String description() {
        return "Aktiviert oder deaktiviert einen Haus-Modus. Die entityId vorher "
                + "mit list_modes ermitteln.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "entityId", Map.of("type", "string", "description", "Entity-ID aus list_modes"),
                        "state", Map.of("type", "string", "enum", List.of("on", "off"))),
                "required", List.of("entityId", "state"));
    }

    @Override
    public String execute(Map<String, Object> input) {
        String entityId = String.valueOf(input.get("entityId"));
        String desired = String.valueOf(input.get("state"));

        ModeResponse mode = houseModeQueryService.listModes().stream()
                .filter(m -> m.entityId().equals(entityId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Kein Haus-Modus: " + entityId));

        if (desired.equals(mode.state())) {
            return entityId + " ist bereits " + desired;
        }
        manualEntityService.toggle(entityId);
        return entityId + " ist jetzt " + desired;
    }
}
