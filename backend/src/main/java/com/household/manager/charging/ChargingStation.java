package com.household.manager.charging;

/** Ein Standort aus der Umkreis-Suche (nur Zaehlwerte, keine Ladepunkte einzeln). */
public record ChargingStation(String stationId, String name, String operator, String address,
                              double lat, double lon, Double maxPowerKw, int total, int free) {
}
