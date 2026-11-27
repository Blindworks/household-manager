package com.household.manager.tesla;

import java.time.Instant;

/**
 * Zustand eines Fahrzeugs aus der Tessie-Antwort, in Haushaltseinheiten (km, °C, kW).
 * Felder, die Tessie nicht liefert, sind {@code null}, nie 0.
 *
 * @param chargingState     Tesla-Ladezustand wie geliefert ({@code Charging}, {@code Complete},
 *                          {@code Disconnected}, {@code Stopped}, {@code Starting}, {@code NoPower})
 * @param scheduledChargeAt Beginn des geplanten Ladens, nur wenn eines ansteht
 * @param latitude          letzte bekannte Position (für „Zuhause“)
 * @param optionCodes       Tesla-Ausstattungscodes wie geliefert (z. B. {@code "MDL3,PPSW,W40B"}), für das Fahrzeugbild
 * @param measuredAt        Zeitpunkt des Ladezustands in der Tessie-Antwort
 */
public record TeslaVehicleSnapshot(
        String vin,
        String displayName,
        String vehicleState,
        Integer batteryLevel,
        Double rangeKm,
        Integer chargeLimit,
        String chargingState,
        Boolean pluggedIn,
        Double chargerPowerKw,
        Integer minutesToFullCharge,
        Boolean scheduledChargingPending,
        Instant scheduledChargeAt,
        Boolean chargePortOpen,
        Boolean locked,
        Boolean climateOn,
        Boolean defrostOn,
        Double insideTemp,
        Double outsideTemp,
        Double latitude,
        Double longitude,
        String shiftState,
        String carType,
        String exteriorColor,
        String wheelType,
        String optionCodes,
        Instant measuredAt
) {

    public boolean isCharging() {
        return "Charging".equalsIgnoreCase(chargingState) || "Starting".equalsIgnoreCase(chargingState);
    }
}
