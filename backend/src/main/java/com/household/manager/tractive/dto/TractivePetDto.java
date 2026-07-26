package com.household.manager.tractive.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** Gebuendelte Sicht eines Haustiers fuer die Kartenseite. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TractivePetDto(
        String trackerId,
        String name,
        Double latitude,
        Double longitude,
        Double accuracy,
        String sensorUsed,
        Instant lastSeen,
        Integer batteryPercent,
        Boolean charging,
        String zone
) {
}
