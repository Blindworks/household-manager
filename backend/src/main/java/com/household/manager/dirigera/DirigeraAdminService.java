package com.household.manager.dirigera;

import com.household.manager.audit.AuditService;
import com.household.manager.dirigera.DirigeraDtos.HubView;
import com.household.manager.dirigera.DirigeraDtos.SensorView;
import com.household.manager.dirigera.DirigeraEntityMapper.Measurement;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.DirigeraSensor;
import com.household.manager.model.entity.EntityTileVisibility;
import com.household.manager.repository.DirigeraSensorRepository;
import com.household.manager.repository.EntityTileVisibilityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Admin-Sicht auf den Dirigera-Hub und seine Sensoren. Loeschen nur fuer Sensoren, die der Hub
 * nicht mehr kennt — sonst legte der naechste Poll alles wieder an. Die Kachelregeln muessen
 * explizit mit (kein Fremdschluessel auf entity_states, sonst griffe eine verwaiste NEVER-Zeile
 * beim naechsten gleichnamigen Geraet wieder).
 */
@Service
@RequiredArgsConstructor
public class DirigeraAdminService {

    private final DirigeraSettingsService settingsService;
    private final DirigeraPairingService pairingService;
    private final DirigeraPollingService pollingService;
    private final DirigeraSensorRepository sensorRepository;
    private final EntityTileVisibilityRepository tileVisibilityRepository;
    private final EntityStateService entityStateService;
    private final AuditService auditService;

    public HubView hub() {
        DirigeraHubSettings settings = settingsService.get();
        DirigeraPollingService.PollStatus status = pollingService.lastStatus();
        return new HubView(settings.host(), settings.isPaired(), settings.hubId(),
                status.lastSuccessAt(), status.lastError());
    }

    public HubView updateHost(String host) {
        pairingService.updateHost(host);
        return hub();
    }

    @Transactional(readOnly = true)
    public List<SensorView> sensors() {
        return sensorRepository.findAll().stream()
                .sorted(Comparator.comparing(DirigeraSensor::getName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(SensorView::from)
                .toList();
    }

    @Transactional
    public void deleteSensor(Long id) {
        DirigeraSensor sensor = sensorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dirigera-Sensor", "id", id));
        if (sensor.isKnownToHub()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Der Sensor ist am Hub noch bekannt — zuerst dort entfernen.");
        }
        List<String> entityIds = Arrays.stream(Measurement.values())
                .map(measurement -> DirigeraEntityMapper.entityId(sensor.getRelationId(), measurement))
                .toList();
        List<EntityTileVisibility> rules = tileVisibilityRepository.findByEntityIdIn(entityIds);
        tileVisibilityRepository.deleteAll(rules);
        entityIds.forEach(entityStateService::deleteByEntityId);
        sensorRepository.delete(sensor);
        auditService.record("dirigera.sensor.delete", sensor.getName());
    }
}
