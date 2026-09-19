package com.household.manager.charging;

import java.util.List;

/** Detailantwort zu einer Station: die Ladepunkte einzeln. */
public record ChargingStationDetails(String stationId, List<ChargePoint> chargePoints) {
}
