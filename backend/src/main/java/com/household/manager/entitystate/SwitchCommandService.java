package com.household.manager.entitystate;

import com.household.manager.audit.AuditService;
import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.mapper.SwitchResponseMapper;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityUsage;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
import com.household.manager.service.SmartDeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Schaltet Entitäten quellenübergreifend über ihre Entity-ID: manuelle
 * Boolean-Helfer über den {@link ManualEntityService}, SmartDevice-Steckdosen
 * über den {@link SmartDeviceService}. Erfolgreiche Vorgänge werden gezählt.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SwitchCommandService {

    private static final String STATE_ON = "on";

    private final EntityStateService entityStateService;
    private final ManualEntityService manualEntityService;
    private final SmartDeviceService smartDeviceService;
    private final SmartDeviceRepository smartDeviceRepository;
    private final EntityUsageService entityUsageService;
    private final SwitchResponseMapper switchResponseMapper;
    private final AuditService auditService;

    /**
     * Schaltet die Entität um und zählt den Vorgang.
     *
     * @throws ResourceNotFoundException wenn die Entity-ID unbekannt ist oder kein Gerät dazu existiert
     * @throws IllegalArgumentException  wenn die Entität nicht schaltbar ist
     */
    public SwitchResponse toggle(String entityId) {
        EntityState entity = entityStateService.getByEntityId(entityId)
                .orElseThrow(() -> new ResourceNotFoundException("Entity not found: " + entityId));
        if (!SwitchableEntities.isSwitchable(entity)) {
            throw new IllegalArgumentException("Entity is not switchable: " + entityId);
        }

        if (entity.getDomain() == EntityDomain.INPUT_BOOLEAN) {
            manualEntityService.toggle(entityId);
        } else {
            toggleDevice(entity);
        }

        EntityUsage usage = entityUsageService.recordToggle(entityId);
        auditService.record("switch.toggle", entityId);
        return switchResponseMapper.toResponse(reload(entityId), usage);
    }

    /**
     * Schaltet ein SmartDevice anhand des zuletzt bekannten Zustands; alles außer
     * "on" (auch "unavailable") führt zum Einschalten.
     */
    private void toggleDevice(EntityState entity) {
        DeviceType deviceType = DeviceType.valueOf(entity.getSource().name());
        SmartDevice device = smartDeviceRepository
                .findByDeviceTypeAndExternalDeviceId(deviceType, entity.getSourceRef())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No device for entity: " + entity.getEntityId()));

        if (STATE_ON.equals(entity.getState())) {
            smartDeviceService.turnOff(device.getId());
        } else {
            smartDeviceService.turnOn(device.getId());
        }
    }

    /**
     * Lädt den Zustand nach dem Schaltbefehl neu. Beide Schaltwege melden ihren
     * neuen Zustand selbst an die Entity-Schicht, daher ist er hier bereits aktuell.
     */
    private EntityState reload(String entityId) {
        return entityStateService.getByEntityId(entityId)
                .orElseThrow(() -> new IllegalStateException("Entity disappeared while toggling: " + entityId));
    }
}
