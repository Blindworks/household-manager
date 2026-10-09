package com.household.manager.thermostat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.household.manager.model.entity.Thermostat;
import com.household.manager.tapo.hub.ThermostatMode;
import com.household.manager.tapo.hub.ThermostatReading;

import java.time.LocalDateTime;

/**
 * API-Darstellung eines Thermostats. {@code null} bei Temperaturen und {@code lastUpdated}
 * heisst "unbekannt" und wird bewusst mitgeliefert (Vertrag mit dem Frontend).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ThermostatDto(
        Long id,
        String name,
        boolean online,
        Double currentTemp,
        Double targetTemp,
        ThermostatMode mode,
        boolean heating,
        boolean batteryLow,
        LocalDateTime lastUpdated) {

    /** Ohne Cache-Eintrag (noch nie gelesen): offline, keine Werte, nie ein geratener Zustand. */
    static ThermostatDto from(Thermostat thermostat, ThermostatStateCache.Entry entry) {
        if (entry == null) {
            return new ThermostatDto(thermostat.getId(), thermostat.getName(), false, null, null,
                    ThermostatMode.MANUAL, false, false, null);
        }
        ThermostatReading reading = entry.reading();
        return new ThermostatDto(thermostat.getId(), thermostat.getName(), reading.online(),
                reading.currentTemp(), reading.targetTemp(), reading.mode(), reading.heating(),
                reading.batteryLow(), entry.lastUpdated());
    }
}
