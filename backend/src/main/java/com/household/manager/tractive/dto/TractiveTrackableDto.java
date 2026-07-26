package com.household.manager.tractive.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Haustier-Details aus {@code GET /trackable_object/{id}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TractiveTrackableDto(
        @JsonProperty("_id") String id,
        @JsonProperty("device_id") String deviceId,
        Details details
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Details(String name, @JsonProperty("pet_type") String petType) {
    }

    /** Anzeigename mit Rueckfall auf die Geraete-ID. */
    public String displayName() {
        return details != null && details.name() != null && !details.name().isBlank()
                ? details.name()
                : "Tracker " + deviceId;
    }
}
