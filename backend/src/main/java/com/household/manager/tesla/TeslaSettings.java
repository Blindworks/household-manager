package com.household.manager.tesla;

/**
 * Nutzereinstellungen der Tesla-Anbindung (Admin-Seite).
 *
 * @param apiToken            Tessie-Zugangstoken; nie an das Frontend geben
 * @param vin                 gewähltes Fahrzeug, {@code null} = das erste des Kontos
 * @param pollIntervalMinutes Abstand zwischen zwei Abrufen
 */
public record TeslaSettings(String apiToken, String vin, int pollIntervalMinutes) {

    public boolean hasToken() {
        return apiToken != null && !apiToken.isBlank();
    }

    @Override
    public String toString() {
        return "TeslaSettings[token=" + (hasToken() ? "***" : "-") + ", vin=" + vin
                + ", pollIntervalMinutes=" + pollIntervalMinutes + "]";
    }
}
