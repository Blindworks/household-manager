package com.household.manager.charging.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnbwTariffInfoDto(@JsonProperty("tariffGroup") String tariffGroup,
                                @JsonProperty("tariffDescription") String tariffDescription) {
}
