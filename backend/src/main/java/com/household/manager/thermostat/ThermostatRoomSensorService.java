package com.household.manager.thermostat;

import com.household.manager.audit.AuditService;
import com.household.manager.dto.CurrentTemperatureReading;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.Thermostat;
import com.household.manager.repository.ThermostatRepository;
import com.household.manager.service.TemperatureSeriesService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Ordnet einem Thermostat den Temperaturfuehler seines Raums zu. Die Zuordnung ist bewusst
 * explizit (Admin-Seite) statt ueber Namen geraten: Thermostat- und Fuehlernamen sind frei
 * vergeben ("Wohnzimmerfenster" vs. "Temperatur Wohnzimmer"), ein Umbenennen braeche sonst still.
 */
@Service
@RequiredArgsConstructor
public class ThermostatRoomSensorService {

    /** Die DWD-Vorhersage ist kein Fuehler in einem Raum. */
    static final String WEATHER_SENSOR_ID = "weather:outdoor";

    private final ThermostatRepository repository;
    private final ThermostatStateCache cache;
    private final TemperatureSeriesService temperatureSeriesService;
    private final AuditService auditService;
    private final ThermostatRoomRegulator regulator;

    static final double MIN_ROOM_TARGET = 10.0;
    static final double MAX_ROOM_TARGET = 28.0;

    /**
     * @param sensorId sensorId aus {@code /v1/temperatures/current}; {@code null} oder leer entfernt
     *                 die Zuordnung. Ein Fuehler, der dort gerade nicht gelistet ist, wird abgelehnt
     *                 (400) - sonst liesse sich ein Tippfehler speichern, der nie einen Wert zeigt.
     */
    public ThermostatDto setRoomSensor(Long id, String sensorId) {
        Thermostat thermostat = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Thermostat", "id", id));
        String normalized = sensorId == null || sensorId.isBlank() ? null : sensorId.trim();
        String label = normalized == null ? "kein Raumfuehler" : requireKnownSensor(normalized);

        thermostat.setRoomSensorId(normalized);
        repository.save(thermostat);
        auditService.record("thermostat.room-sensor",
                thermostat.getName() + " (" + id + ") -> " + label);
        return ThermostatDto.from(thermostat, cache.find(thermostat.getId()).orElse(null));
    }

    /**
     * Raum-Solltemperatur fuer die Raumregelung ({@link ThermostatRoomRegulator}). Erlaubt sind
     * 10,0-28,0 in 0,5-Schritten; {@code null} beendet die Regelung (der zuletzt geschriebene
     * Sollwert bleibt am Thermostat stehen). Ohne Raumfuehler wird abgelehnt - die Regelung hat
     * dann nichts, worauf sie regeln koennte.
     */
    public ThermostatDto setRoomTarget(Long id, Double roomTarget) {
        Thermostat thermostat = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Thermostat", "id", id));
        if (roomTarget != null) {
            validateRoomTarget(roomTarget);
            if (thermostat.getRoomSensorId() == null) {
                throw new IllegalArgumentException("Erst einen Raumfuehler zuordnen.");
            }
        }
        thermostat.setRoomTarget(roomTarget);
        repository.save(thermostat);
        regulator.forget(id);
        auditService.record("thermostat.room-target", thermostat.getName() + " (" + id + ") -> "
                + (roomTarget == null ? "keine Raumregelung" : roomTarget));
        return ThermostatDto.from(thermostat, cache.find(thermostat.getId()).orElse(null));
    }

    static void validateRoomTarget(double roomTarget) {
        boolean onGrid = Math.abs(roomTarget * 2 - Math.round(roomTarget * 2)) < 1e-9;
        if (!Double.isFinite(roomTarget) || roomTarget < MIN_ROOM_TARGET || roomTarget > MAX_ROOM_TARGET || !onGrid) {
            throw new IllegalArgumentException("Raum-Soll muss zwischen 10,0 und 28,0 Grad in 0,5-Schritten liegen.");
        }
    }

    private String requireKnownSensor(String sensorId) {
        if (WEATHER_SENSOR_ID.equals(sensorId)) {
            throw new IllegalArgumentException("Die Wettervorhersage ist kein Raumfuehler.");
        }
        return temperatureSeriesService.getCurrent().stream()
                .filter(reading -> sensorId.equals(reading.getSensorId()))
                .findFirst()
                .map(CurrentTemperatureReading::getName)
                .map(name -> name + " (" + sensorId + ")")
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unbekannter Temperaturfuehler: " + sensorId));
    }
}
