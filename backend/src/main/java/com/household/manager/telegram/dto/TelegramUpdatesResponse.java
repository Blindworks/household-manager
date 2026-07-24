package com.household.manager.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramUpdatesResponse(boolean ok, List<TelegramUpdate> result) {
}
