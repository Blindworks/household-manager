package com.household.manager.charging;

import java.time.Instant;

/**
 * Ein Ladepunkt (EVSE) einer Station. {@code statusSince} ist der von der Quelle gemeldete
 * Zeitpunkt des letzten Statuswechsels (z. B. EnBWs {@code state.updatedAt}); {@code null}
 * bei Quellen, die das nicht liefern - dann greift weiterhin die eigene Poll-Historie in
 * {@link ChargingOccupancyTracker}. {@code pricePerKwh} ist der aus dem Tarif der Quelle
 * geparste Preis je kWh (EnBW: {@code tariffDescription}), {@code null} wenn nicht ermittelbar.
 */
public record ChargePoint(String chargePointId, ChargePointStatus status, Double maxPowerKw, String connector,
                          Instant statusSince, Double pricePerKwh) {
}
