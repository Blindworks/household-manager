package com.household.manager.thermostat;

import com.household.manager.audit.AuditService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.Thermostat;
import com.household.manager.repository.ThermostatRepository;
import com.household.manager.tapo.hub.ThermostatMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Einzige Stelle, an der ein vom Menschen gewaehlter Sollwert ankommt (Tablet-Heizung,
 * 3D-Ansicht, Hausverstand, Flow-Aktion {@code target}).
 *
 * <p>Hat ein Thermostat einen Raumfuehler, ist der gewaehlte Wert die gewuenschte
 * <b>Raumtemperatur</b>: er wird als Raum-Soll gespeichert, und {@link ThermostatRoomRegulator}
 * stellt den Sollwert am Heizkoerper sofort und danach alle 5 Minuten so nach, dass der Raum ihn
 * erreicht. Ohne Raumfuehler geht der Wert wie bisher direkt ans Thermostat. Ein Wert von 5,0
 * (Aus) geht immer direkt ans Thermostat und laesst das Raum-Soll stehen - die Regelung greift
 * bei Aus ohnehin nicht ein.
 */
@Service
@RequiredArgsConstructor
public class ThermostatSetpointService {

    private final ThermostatRepository repository;
    private final ThermostatService thermostatService;
    private final ThermostatRoomRegulator regulator;
    private final AuditService auditService;

    public ThermostatDto setTarget(Long id, double targetTemp) {
        ThermostatTargetValidator.validate(targetTemp);
        Thermostat thermostat = load(id);
        if (!isRoomRegulated(thermostat, targetTemp)) {
            return thermostatService.setTarget(id, targetTemp);
        }
        thermostat.setRoomTarget(targetTemp);
        repository.save(thermostat);
        auditService.record("thermostat.room-target", thermostat.getName() + " (" + id + ") -> " + targetTemp);
        return regulator.regulateNow(thermostat);
    }

    /** MANUAL mit Raum-Soll regelt sofort, statt den Standard-Sollwert 17 stehen zu lassen. */
    public ThermostatDto setMode(Long id, ThermostatMode mode) {
        ThermostatDto result = thermostatService.setMode(id, mode);
        Thermostat thermostat = load(id);
        if (mode == ThermostatMode.MANUAL && hasRoomRegulation(thermostat)) {
            return regulator.regulateNow(thermostat);
        }
        if (mode != ThermostatMode.MANUAL) {
            regulator.forget(id);
        }
        return result;
    }

    /** „Alle auf 21": je Thermostat wie {@link #setTarget}, Thermostate mit Raumfuehler als Raum-Soll. */
    public List<ThermostatDto> bulk(List<Long> ids, double targetTemp) {
        List<ThermostatDto> written = thermostatService.bulk(ids, targetTemp);
        return written.stream().map(dto -> {
            Thermostat thermostat = load(dto.id());
            return isRoomRegulated(thermostat, targetTemp) ? setTarget(dto.id(), targetTemp) : dto;
        }).toList();
    }

    /** Hat das Thermostat eine eingerichtete Raumregelung (Raumfuehler und Raum-Soll)? */
    public boolean hasRoomRegulation(Long id) {
        return hasRoomRegulation(load(id));
    }

    private static boolean isRoomRegulated(Thermostat thermostat, double targetTemp) {
        return thermostat.getRoomSensorId() != null && targetTemp > ThermostatService.OFF_TARGET_TEMP;
    }

    private static boolean hasRoomRegulation(Thermostat thermostat) {
        return thermostat.getRoomSensorId() != null && thermostat.getRoomTarget() != null;
    }

    private Thermostat load(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Thermostat", "id", id));
    }
}
