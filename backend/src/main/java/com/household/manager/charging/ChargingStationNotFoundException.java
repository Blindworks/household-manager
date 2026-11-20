package com.household.manager.charging;

/**
 * Die Quelle kennt die angefragte Station nicht (HTTP 404). Bei einem Favoriten heisst das meist,
 * dass der Betreiber die Saeule neu angelegt hat und sie unter einer neuen Id weiterlaeuft
 * (real 2026-11: Mainova, Am Suedbahnhof 3). Bleibt ein {@link ChargingSourceException} (502).
 */
public class ChargingStationNotFoundException extends ChargingSourceException {
    public ChargingStationNotFoundException(String message) {
        super(message);
    }
}
