package com.household.manager.charging.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Eine Zeile der Umkreis-Antwort. Die echte API liefert weder {@code stationName} noch ein
 * strukturiertes Adressobjekt - nur {@code shortAddress} als Freitext - und gruppiert grosse
 * Boxen serverseitig zu Sammelzeilen ({@code grouped: true}, {@code stationId: null}) mit
 * einem {@code viewPort} statt einer einzelnen Koordinate.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EnbwStationDto(
        @JsonProperty("stationId") String stationId,
        @JsonProperty("grouped") Boolean grouped,
        @JsonProperty("operator") String operator,
        @JsonProperty("shortAddress") String shortAddress,
        @JsonProperty("lat") Double lat,
        @JsonProperty("lon") Double lon,
        @JsonProperty("maxPowerInKw") Double maxPowerInKw,
        @JsonProperty("numberOfChargePoints") Integer numberOfChargePoints,
        @JsonProperty("availableChargePoints") Integer availableChargePoints,
        @JsonProperty("viewPort") EnbwViewPortDto viewPort) {
}
