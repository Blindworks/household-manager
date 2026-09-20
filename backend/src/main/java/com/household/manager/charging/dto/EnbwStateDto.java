package com.household.manager.charging.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code updatedAt} ist der Zeitpunkt des letzten Statuswechsels (Epoch-Millisekunden). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EnbwStateDto(
        @JsonProperty("updatedAt") Long updatedAt,
        @JsonProperty("value") String value) {
}
