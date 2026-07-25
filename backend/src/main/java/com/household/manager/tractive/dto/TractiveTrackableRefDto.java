package com.household.manager.tractive.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Eintrag aus {@code GET /user/{userId}/trackable_objects}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TractiveTrackableRefDto(@JsonProperty("_id") String id) {
}
