package com.household.manager.thermostat;

import com.household.manager.audit.AuditService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.Thermostat;
import com.household.manager.repository.ThermostatRepository;
import com.household.manager.tapo.TapoException;
import com.household.manager.tapo.hub.TapoHubClient;
import com.household.manager.tapo.hub.ThermostatMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lesen und Bedienen der Thermostate. Schreiben validiert vor jedem Geraetezugriff, liest den
 * Hub danach einmal nach (ein Aufruf je Hub) und auditiert erst NACH dem Erfolg. Die
 * Entity-Spiegelung uebernimmt der Poller; nach einem Schreibvorgang aktualisiert der Service
 * nur den Cache, die Entitaeten folgen mit dem naechsten Poll.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ThermostatService {

    /** Das Geraet kennt kein echtes "Aus": Modus OFF ist diese Zieltemperatur. */
    static final double OFF_TARGET_TEMP = 5.0;
    static final int MAX_NAME_LENGTH = 100;

    private final ThermostatRepository repository;
    private final TapoHubClient hubClient;
    private final ThermostatStateCache cache;
    private final AuditService auditService;
    private final Clock clock;

    public List<ThermostatDto> list() {
        return repository.findByActiveTrue().stream().map(this::toDto).toList();
    }

    public ThermostatDto setTarget(Long id, double targetTemp) {
        ThermostatTargetValidator.validate(targetTemp);
        Thermostat thermostat = load(id);
        String hubIp = requireHubIp(thermostat);

        hubClient.setTargetTemp(hubIp, thermostat.getChildDeviceId(), targetTemp);
        refreshCache(hubIp);
        auditService.record("thermostat.target", thermostat.getName() + " (" + id + ") -> " + targetTemp);
        return toDto(thermostat);
    }

    public ThermostatDto setMode(Long id, ThermostatMode mode) {
        Thermostat thermostat = load(id);
        String hubIp = requireHubIp(thermostat);

        applyMode(hubIp, thermostat.getChildDeviceId(), mode);
        refreshCache(hubIp);
        auditService.record("thermostat.mode", thermostat.getName() + " (" + id + ") -> " + mode);
        return toDto(thermostat);
    }

    /** Der Name ist reine Anzeige; die Entity-Ids haengen an der DB-Id und bleiben unberuehrt. */
    public ThermostatDto rename(Long id, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Der Name muss zwischen 1 und " + MAX_NAME_LENGTH + " Zeichen lang sein.");
        }
        Thermostat thermostat = load(id);
        String oldName = thermostat.getName();
        thermostat.setName(trimmed);
        repository.save(thermostat);
        auditService.record("thermostat.rename", id + ": " + oldName + " -> " + trimmed);
        return toDto(thermostat);
    }

    /**
     * @param ids {@code null} = alle aktiven Thermostate mit gepflegter Hub-IP
     */
    public List<ThermostatDto> bulk(List<Long> ids, double targetTemp) {
        ThermostatTargetValidator.validate(targetTemp);
        List<Thermostat> targets = resolveBulkTargets(ids);

        Set<String> hubIps = new LinkedHashSet<>();
        for (Thermostat thermostat : targets) {
            String hubIp = requireHubIp(thermostat);
            hubClient.setTargetTemp(hubIp, thermostat.getChildDeviceId(), targetTemp);
            hubIps.add(hubIp);
        }
        hubIps.forEach(this::refreshCache);
        auditService.record("thermostat.bulk", targets.size() + " Thermostate -> " + targetTemp
                + " (" + targets.stream().map(Thermostat::getName).toList() + ")");
        return targets.stream().map(this::toDto).toList();
    }

    private List<Thermostat> resolveBulkTargets(List<Long> ids) {
        if (ids == null) {
            return repository.findByActiveTrue().stream().filter(t -> hasHubIp(t)).toList();
        }
        return ids.stream().distinct().map(this::load).toList();
    }

    private void applyMode(String hubIp, String childId, ThermostatMode mode) {
        switch (mode) {
            case OFF -> {
                hubClient.setTargetTemp(hubIp, childId, OFF_TARGET_TEMP);
                hubClient.setFrostProtection(hubIp, childId, false);
            }
            case FROST_PROTECTION -> hubClient.setFrostProtection(hubIp, childId, true);
            case MANUAL -> hubClient.setFrostProtection(hubIp, childId, false);
        }
    }

    /**
     * Der Schreibvorgang war schon erfolgreich; scheitert nur das Nachlesen, bleibt der alte
     * Cache stehen und der naechste Poll holt den Zustand nach.
     */
    private void refreshCache(String hubIp) {
        try {
            var readings = hubClient.readThermostats(hubIp);
            cache.applyHubReadings(repository.findByHubIp(hubIp), readings, LocalDateTime.now(clock));
        } catch (TapoException ex) {
            log.warn("Nachlesen des Hubs {} nach Schreibvorgang fehlgeschlagen: {}", hubIp, ex.getMessage());
        }
    }

    private Thermostat load(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Thermostat", "id", id));
    }

    private boolean hasHubIp(Thermostat thermostat) {
        return thermostat.getHubIp() != null && !thermostat.getHubIp().isBlank();
    }

    private String requireHubIp(Thermostat thermostat) {
        if (!hasHubIp(thermostat)) {
            throw new IllegalStateException("Fuer Thermostat '" + thermostat.getName()
                    + "' ist keine Hub-IP hinterlegt.");
        }
        return thermostat.getHubIp();
    }

    private ThermostatDto toDto(Thermostat thermostat) {
        return ThermostatDto.from(thermostat, cache.find(thermostat.getId()).orElse(null));
    }
}
