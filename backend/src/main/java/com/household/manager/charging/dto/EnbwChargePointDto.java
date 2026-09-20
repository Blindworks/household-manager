package com.household.manager.charging.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnbwChargePointDto(@JsonProperty("evseId") String evseId,
                                 @JsonProperty("status") String status,
                                 @JsonProperty("state") EnbwStateDto state,
                                 @JsonProperty("connectors") List<EnbwConnectorDto> connectors) {
}
