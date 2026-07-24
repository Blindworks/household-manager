package com.household.manager.telegram.tools;

import com.household.manager.nuki.NukiLockAction;
import com.household.manager.nuki.NukiLockService;
import com.household.manager.nuki.dto.NukiLockResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Verriegelt ein Nuki-Schloss. SICHERHEITSANKER: Die Aktion ist im Code fest
 * auf LOCK verdrahtet — Entriegeln/Tuer oeffnen existiert fuer den
 * Telegram-Assistenten bewusst nicht (Prompt-Injection-Schutz).
 */
@Component
@RequiredArgsConstructor
public class LockDoorTool implements AgentTool {

    private final NukiLockService nukiLockService;

    @Override
    public String name() {
        return "lock_door";
    }

    @Override
    public String description() {
        return "Verriegelt die Tuer (nur abschliessen — aufschliessen ist ueber "
                + "diesen Weg nicht moeglich). Ohne smartlockId wird das einzige "
                + "vorhandene Schloss verriegelt.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "smartlockId", Map.of("type", "string",
                                "description", "Optional, aus get_lock_status; noetig bei mehreren Schloessern")));
    }

    @Override
    public String execute(Map<String, Object> input) {
        long smartlockId = resolveSmartlockId(input.get("smartlockId"));
        nukiLockService.executeAction(smartlockId, NukiLockAction.LOCK);
        return "Schloss " + smartlockId + " wird verriegelt.";
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
            throw new IllegalArgumentException(
                    "smartlockId noetig — es gibt " + locks.size() + " Schloesser (siehe get_lock_status)");
        }
        return locks.get(0).smartlockId();
    }
}
