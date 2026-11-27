package com.household.manager.tesla;

/**
 * Nutzereinstellungen der Tesla-Anbindung (Admin-Seite).
 *
 * @param apiToken            Tessie-Zugangstoken; nie an das Frontend geben
 * @param vin                 gewähltes Fahrzeug, {@code null} = das erste des Kontos
 * @param pollIntervalMinutes Abstand zwischen zwei Abrufen
 * @param imageUrl            eigene Bildadresse des Autos, {@code null} = aus Teslas Konfigurator gebaut
 */
public record TeslaSettings(String apiToken, String vin, int pollIntervalMinutes, String imageUrl) {

    public TeslaSettings(String apiToken, String vin, int pollIntervalMinutes) {
        this(apiToken, vin, pollIntervalMinutes, null);
    }

    public boolean hasToken() {
        return apiToken != null && !apiToken.isBlank();
    }

    @Override
    public String toString() {
        return "TeslaSettings[token=" + (hasToken() ? "***" : "-") + ", vin=" + vin
                + ", pollIntervalMinutes=" + pollIntervalMinutes + ", imageUrl=" + imageUrl + "]";
    }
}
