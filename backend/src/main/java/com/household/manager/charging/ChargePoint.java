package com.household.manager.charging;

/** Ein Ladepunkt (EVSE) einer Station. */
public record ChargePoint(String chargePointId, ChargePointStatus status, Double maxPowerKw, String connector) {
}
