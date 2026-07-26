package com.household.manager.nuki;

import com.household.manager.audit.AuditService;
import com.household.manager.nuki.dto.NukiLockResponse;
import com.household.manager.nuki.dto.NukiSmartlockDto;
import com.household.manager.nuki.dto.NukiSmartlockStateDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fachlogik für die Schloss-Endpoints: Liste für die Kachel und
 * Aktionen mit sofortigem Nachpollen (Entitäten hängen sonst bis zu
 * einem Poll-Intervall hinterher).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NukiLockService {

    private final NukiApiClient apiClient;
    private final NukiPollingService pollingService;
    private final AuditService auditService;

    public List<NukiLockResponse> listLocks() {
        return apiClient.listSmartlocks().stream()
                .map(this::toResponse)
                .toList();
    }

    public void executeAction(long smartlockId, NukiLockAction action) {
        log.info("Nuki action {} for smartlock {}", action, smartlockId);
        apiClient.sendAction(smartlockId, action.getApiCode());
        auditService.record("nuki." + action.name().toLowerCase(), String.valueOf(smartlockId));
        pollingService.poll();
    }

    private NukiLockResponse toResponse(NukiSmartlockDto smartlock) {
        NukiSmartlockStateDto state = smartlock.state();
        return new NukiLockResponse(
                smartlock.smartlockId(),
                smartlock.name(),
                NukiLockStates.lockState(state != null ? state.state() : null),
                NukiLockStates.doorState(state != null ? state.doorState() : null),
                state != null ? state.batteryCharge() : null,
                state != null && Boolean.TRUE.equals(state.batteryCritical()));
    }
}
