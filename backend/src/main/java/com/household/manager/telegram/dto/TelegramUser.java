package com.household.manager.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Absender eines Knopfdrucks; der Vorname landet als "entschieden von" im Vorschlag. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramUser(long id, @JsonProperty("first_name") String firstName) {
}
