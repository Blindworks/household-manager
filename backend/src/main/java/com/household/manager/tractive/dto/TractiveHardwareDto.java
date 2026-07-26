package com.household.manager.tractive.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Hardware-Bericht aus {@code GET /device_hw_report/{trackerId}/}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TractiveHardwareDto(
        @JsonProperty("battery_level") Integer batteryLevel,
        @JsonProperty("charging_state") String chargingState
) {

    public boolean isCharging() {
        return "CHARGING".equalsIgnoreCase(chargingState);
    }
}
