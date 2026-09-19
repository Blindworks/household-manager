package com.household.manager.charging.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnbwStationDto(
        @JsonProperty("stationId") String stationId,
        @JsonProperty("stationName") String stationName,
        @JsonProperty("operator") String operator,
        @JsonProperty("lat") Double lat,
        @JsonProperty("lon") Double lon,
        @JsonProperty("maxPowerInKw") Double maxPowerInKw,
        @JsonProperty("numberOfChargePoints") Integer numberOfChargePoints,
        @JsonProperty("availableChargePoints") Integer availableChargePoints,
        @JsonProperty("address") EnbwAddressDto address) {
}
