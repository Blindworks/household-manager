package com.household.manager.charging.supercharger;

/**
 * Ein Tesla-Supercharger-Standort aus supercharge.info. Nur Stammdaten, keine Belegung.
 * {@code lat}/{@code lon} sind nullbar, weil die Quelle Eintraege ohne GPS fuehrt.
 */
public record SuperchargerSite(String id, String name, String street, String city, Double lat, Double lon,
                               int stallCount, Integer powerKw, boolean otherEvs, String status) {
}
