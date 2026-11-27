package com.household.manager.tesla.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/** Fahrzeug für die Dashboard-Kachel. Zeiten in Haushaltszeit. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeslaVehicleView(
        String vin,
        String displayName,
        String vehicleState,
        Integer batteryLevel,
        Double rangeKm,
        Integer chargeLimit,
        String chargingState,
        Boolean charging,
        Boolean pluggedIn,
        Double chargerPowerKw,
        Integer minutesToFullCharge,
        Boolean scheduledChargingPending,
        LocalDateTime scheduledChargeAt,
        Boolean chargePortOpen,
        Boolean locked,
        Boolean climateOn,
        Boolean defrostOn,
        Double insideTemp,
        Double outsideTemp,
        Boolean atHome,
        String shiftState,
        String carType,
        String exteriorColor,
        LocalDateTime measuredAt
) {
}
