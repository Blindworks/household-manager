package com.household.manager.charging.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Umschliessendes Rechteck einer (moeglicherweise gruppierten) Umkreis-Antwortzeile. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EnbwViewPortDto(
        @JsonProperty("lowerLeftLat") Double lowerLeftLat,
        @JsonProperty("lowerLeftLon") Double lowerLeftLon,
        @JsonProperty("upperRightLat") Double upperRightLat,
        @JsonProperty("upperRightLon") Double upperRightLon) {
}
