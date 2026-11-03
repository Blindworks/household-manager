package com.household.manager.dirigera;

import com.household.manager.audit.AuditService;
import com.household.manager.dirigera.DirigeraDtos.ButtonView;
import com.household.manager.dirigera.DirigeraDtos.HubView;
import com.household.manager.dirigera.DirigeraDtos.SensorView;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.DirigeraSensor;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityTileVisibility;
import com.household.manager.repository.DirigeraSensorRepository;
import com.household.manager.repository.EntityTileVisibilityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Admin-Sicht auf den Dirigera-Hub und seine Sensoren. Loeschen nur fuer Sensoren, die der Hub
 * nicht mehr kennt — sonst legte der naechste Poll alles wieder an. Die Kachelregeln muessen
 * explizit mit (kein Fremdschluessel auf entity_states, sonst griffe eine verwaiste NEVER-Zeile
 * beim naechsten gleichnamigen Geraet wieder). Taster haben keine Zeile: ihre Liste kommt aus dem
 * letzten Poll ({@link DirigeraButtonRegistry}), nach einem Neustart ist sie bis zum ersten Poll leer.
 */
@Service
@RequiredArgsConstructor
public class DirigeraAdminService {

    private final DirigeraSettingsService settingsService;
    private final DirigeraPairingService pairingService;
    private final DirigeraPollingService pollingService;
    private final DirigeraEventStreamService eventStreamService;
    private final DirigeraButtonRegistry buttonRegistry;
    private final DirigeraSensorRepository sensorRepository;
    private final EntityTileVisibilityRepository tileVisibilityRepository;
    private final EntityStateService entityStateService;
    private final AuditService auditService;

    private static final Map<String, Integer> POSITION_ORDER = Map.of("top", 0, "bottom", 1);

    public HubView hub() {
        DirigeraHubSettings settings = settingsService.get();
        DirigeraPollingService.PollStatus status = pollingService.lastStatus();
        return new HubView(settings.host(), settings.isPaired(), settings.hubId(),
                status.lastSuccessAt(), status.lastError(), eventStreamService.isConnected());
    }

    /** Je Taste eine Zeile, nach Namen, innerhalb eines Tasters oben vor unten. */
    public List<ButtonView> buttons() {
        return buttonRegistry.all().stream()
                .sorted(Comparator.comparing(DirigeraButton::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(button -> POSITION_ORDER.getOrDefault(button.position(), POSITION_ORDER.size()))
                        .thenComparing(DirigeraButton::deviceId))
                .map(this::buttonView)
                .toList();
    }

    private ButtonView buttonView(DirigeraButton button) {
        String entityId = DirigeraEntityMapper.buttonEntityId(button.deviceId());
        Optional<EntityState> state = entityStateService.getByEntityId(entityId);
        return new ButtonView(button.deviceId(), entityId, button.name(), button.position(), button.room(),
                button.model(), button.batteryPercent(),
                state.map(EntityState::getState).orElse(null),
                state.map(EntityState::getLastChanged).orElse(null));
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
        List<String> entityIds = DirigeraEntityMapper.entityIds(sensor);
        List<EntityTileVisibility> rules = tileVisibilityRepository.findByEntityIdIn(entityIds);
        tileVisibilityRepository.deleteAll(rules);
        entityIds.forEach(entityStateService::deleteByEntityId);
        sensorRepository.delete(sensor);
        auditService.record("dirigera.sensor.delete", sensor.getName());
    }
}
