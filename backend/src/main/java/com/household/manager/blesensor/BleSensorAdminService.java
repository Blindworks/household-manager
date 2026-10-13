package com.household.manager.blesensor;

import com.household.manager.audit.AuditService;
import com.household.manager.blesensor.BleSensorDtos.SensorAdminResponse;
import com.household.manager.blesensor.BleSensorDtos.SensorUpdateRequest;
import com.household.manager.blesensor.BleSensorEntityMapper.Measurement;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.BleSensor;
import com.household.manager.repository.BleSensorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Pflege der Bluetooth-Thermometer: Uebernahme (Name + aktiv), Umbenennen, Deaktivieren, Loeschen.
 * Loeschen nur fuer inaktive Zeilen (409 sonst); dabei werden die Entitaeten entfernt, sonst
 * stuenden sie fuer immer auf unavailable. Deaktivieren meldet die Entitaeten einmal unavailable.
 */
@Service
@RequiredArgsConstructor
public class BleSensorAdminService {

    private final BleSensorRepository repository;
    private final BleSensorEntityMapper mapper;
    private final EntityStateService entityStateService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<SensorAdminResponse> list() {
        return repository.findAllByOrderByActiveAscNameAscMacAsc().stream()
                .map(SensorAdminResponse::from)
                .toList();
    }

    @Transactional
    public SensorAdminResponse update(Long id, SensorUpdateRequest request) {
        BleSensor sensor = findOrThrow(id);
        boolean active = request.active() == null || request.active();
        String name = request.name() == null ? null : request.name().trim();
        if (active && (name == null || name.isBlank())) {
            throw new IllegalArgumentException("Ein aktiver Sensor braucht einen Namen.");
        }
        if (name != null && name.length() > 100) {
            throw new IllegalArgumentException("Der Name darf hoechstens 100 Zeichen haben.");
        }
        boolean wasActive = sensor.isActive();
        sensor.setName(name == null || name.isBlank() ? sensor.getName() : name);
        sensor.setActive(active);
        BleSensor saved = repository.save(sensor);

        if (wasActive && !active) {
            mapper.unavailable(saved).forEach(entityStateService::reportState);
        }
        auditService.record("ble-sensor.update", "%s (%s) aktiv=%s".formatted(saved.getName(), saved.getMac(), saved.isActive()));
        return SensorAdminResponse.from(saved);
    }

    @Transactional
    public void delete(Long id) {
        BleSensor sensor = findOrThrow(id);
        if (sensor.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ein aktiver Sensor kann nicht geloescht werden. Bitte zuerst deaktivieren.");
        }
        repository.delete(sensor);
        for (Measurement measurement : Measurement.values()) {
            entityStateService.deleteByEntityId(BleSensorEntityMapper.entityId(sensor.getMac(), measurement));
        }
        auditService.record("ble-sensor.delete", "%s (%s)".formatted(sensor.getName(), sensor.getMac()));
    }

    private BleSensor findOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BleSensor", "id", id));
    }
}
