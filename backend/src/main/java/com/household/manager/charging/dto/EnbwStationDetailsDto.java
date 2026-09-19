package com.household.manager.charging.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnbwStationDetailsDto(@JsonProperty("stationId") String stationId,
                                    @JsonProperty("chargePoints") List<EnbwChargePointDto> chargePoints) {
}
