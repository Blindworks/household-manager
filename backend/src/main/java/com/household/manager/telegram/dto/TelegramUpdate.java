package com.household.manager.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Ein Update trägt entweder eine Nachricht oder einen Knopfdruck ({@code callbackQuery}), nie beides. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramUpdate(@JsonProperty("update_id") long updateId, TelegramMessage message,
                             @JsonProperty("callback_query") TelegramCallbackQuery callbackQuery) {
}
