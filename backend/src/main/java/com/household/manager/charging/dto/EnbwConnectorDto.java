package com.household.manager.charging.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnbwConnectorDto(@JsonProperty("plugTypeName") String plugTypeName,
                               @JsonProperty("maxPowerInKw") Double maxPowerInKw,
                               @JsonProperty("tariffInfo") EnbwTariffInfoDto tariffInfo) {
}
