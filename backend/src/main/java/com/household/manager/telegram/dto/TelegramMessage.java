package com.household.manager.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramMessage(TelegramChat chat, String text) {
}
