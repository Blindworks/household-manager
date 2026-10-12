package com.household.manager.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code messageId} braucht der Hausverstand, um die Knöpfe eines Vorschlags später wieder zu entfernen. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramMessage(TelegramChat chat, String text, @JsonProperty("message_id") Long messageId) {
}
