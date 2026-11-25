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
        thermostat.setRoomTarget(normalized == null ? null : startingRoomTarget(thermostat));
        repository.save(thermostat);
        regulator.forget(id);
        auditService.record("thermostat.room-sensor",
                thermostat.getName() + " (" + id + ") -> " + label);
        return ThermostatDto.from(thermostat, cache.find(thermostat.getId()).orElse(null));
    }

    /**
     * Beim ersten Zuordnen wird der aktuelle Sollwert zum Raum-Soll: so regelt das Thermostat
     * sofort auf das, was zuletzt eingestellt war. Ein schon gesetztes Raum-Soll bleibt beim
     * Wechsel des Fuehlers stehen; ist das Thermostat aus oder unbekannt, gibt es noch keins
     * (dann setzt es der naechste Dreh am Regler).
     */
    private Double startingRoomTarget(Thermostat thermostat) {
        if (thermostat.getRoomTarget() != null) {
            return thermostat.getRoomTarget();
        }
        return cache.find(thermostat.getId())
                .map(entry -> entry.reading().targetTemp())
                .filter(target -> target > ThermostatService.OFF_TARGET_TEMP)
                .map(target -> Math.round(target * 2.0) / 2.0)
                .orElse(null);
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
