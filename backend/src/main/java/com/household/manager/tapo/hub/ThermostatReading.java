package com.household.manager.tapo.hub;

/**
 * Gelesener Zustand eines Thermostats (KE100) aus der Kinderliste des Hubs.
 * Fehlende Temperaturen sind {@code null}, nie 0.
 */
public record ThermostatReading(
        String childDeviceId,
        String nickname,
        boolean online,
        Double currentTemp,
        Double targetTemp,
        ThermostatMode mode,
        boolean heating,
        boolean batteryLow) {
}
