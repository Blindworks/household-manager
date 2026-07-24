package com.household.manager.telegram.tools;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.SwitchCommandService;
import com.household.manager.model.entity.EntityState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Schaltet eine Entität gezielt auf on/off. Der Umweg über den Ist-Zustand ist
 * nötig, weil der SwitchCommandService nur toggeln kann — sonst würde
 * "schalte an" ein bereits eingeschaltetes Gerät ausschalten.
 */
@Component
@RequiredArgsConstructor
public class SetSwitchTool implements AgentTool {

    private static final String STATE_ON = "on";

    private final EntityStateService entityStateService;
    private final SwitchCommandService switchCommandService;

    @Override
    public String name() {
        return "set_switch";
    }

    @Override
    public String description() {
        return "Schaltet ein Geraet (Licht, Steckdose) ein oder aus. Die entityId "
                + "vorher mit list_switches ermitteln.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "entityId", Map.of("type", "string", "description", "Entity-ID aus list_switches"),
                        "state", Map.of("type", "string", "enum", java.util.List.of("on", "off"))),
                "required", java.util.List.of("entityId", "state"));
    }

    @Override
    public String execute(Map<String, Object> input) {
        String entityId = requireString(input, "entityId");
        String desired = requireString(input, "state");
        if (!"on".equals(desired) && !"off".equals(desired)) {
            throw new IllegalArgumentException("state muss 'on' oder 'off' sein");
        }

        EntityState entity = entityStateService.getByEntityId(entityId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekannte entityId: " + entityId));

        boolean isOn = STATE_ON.equals(entity.getState());
        boolean wantOn = "on".equals(desired);
        if (isOn == wantOn) {
            return entityId + " ist bereits " + desired;
        }
        switchCommandService.toggle(entityId);
        return entityId + " ist jetzt " + desired;
    }

    private String requireString(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(key + " fehlt");
        }
        return String.valueOf(value);
    }
}
