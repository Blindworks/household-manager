package com.household.manager.tesla.dto;

/**
 * @param apiToken {@code null} = unverändert, leer = entfernen
 * @param vin      leer = das erste Fahrzeug des Kontos
 * @param imageUrl leer = Fahrzeugbild aus Teslas Konfigurator
 */
public record TeslaSettingsRequest(String apiToken, String vin, Integer pollIntervalMinutes, String imageUrl) {
}
