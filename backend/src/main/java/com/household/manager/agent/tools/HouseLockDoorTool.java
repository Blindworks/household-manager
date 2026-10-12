package com.household.manager.agent.tools;

import com.household.manager.agent.ActionClass;
import com.household.manager.agent.AgentAction;
import com.household.manager.agent.AgentActionGate;
import com.household.manager.ai.AgentScope;
import com.household.manager.ai.AgentTool;
import com.household.manager.nuki.NukiLockService;
import com.household.manager.nuki.dto.NukiLockResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Verriegelt die Tür über die Schleuse. Wie beim Telegram-Tool gibt es bewusst nur Verriegeln:
 * Entriegeln existiert im Vertrag des Hausverstands nicht (Prompt-Injection-Schutz im Code).
 */
@Component
@RequiredArgsConstructor
public class HouseLockDoorTool implements AgentTool {

    private final AgentActionGate gate;
    private final NukiLockService nukiLockService;

    @Override
    public String name() {
        return "house_lock_door";
    }

    @Override
    public String description() {
        return "Verriegelt die Tür (nur abschließen, aufschließen ist nicht möglich). "
                + "Ohne smartlockId wird das einzige vorhandene Schloss genommen.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "smartlockId", Map.of("type", "string", "description", "Optional; nötig bei mehreren Schlössern"),
                        "reason", ActionInputs.REASON_PROPERTY),
                "required", List.of("reason"));
    }

    @Override
    public AgentScope scope() {
        return AgentScope.HOUSE;
    }

    @Override
    public String execute(Map<String, Object> input) {
        String reason = ActionInputs.reason(input);
        long smartlockId = resolveSmartlockId(input.get("smartlockId"));
        return gate.submit(new AgentAction(ActionClass.LOCK, String.valueOf(smartlockId), Map.of(), reason, false))
                .messageForModel();
    }

    private long resolveSmartlockId(Object raw) {
        if (raw != null && !String.valueOf(raw).isBlank()) {
            try {
                return Long.parseLong(String.valueOf(raw).trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("smartlockId muss numerisch sein: " + raw);
            }
        }
        List<NukiLockResponse> locks = nukiLockService.listLocks();
        if (locks.size() != 1) {
            throw new IllegalArgumentException("smartlockId nötig — es gibt " + locks.size() + " Schlösser.");
        }
        return locks.get(0).smartlockId();
    }
}
