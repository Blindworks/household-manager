package com.household.manager.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramUpdate(@JsonProperty("update_id") long updateId, TelegramMessage message) {
}
