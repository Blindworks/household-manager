package com.household.manager.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Druck auf einen Inline-Knopf. {@code message} ist die Nachricht, an der der Knopf hing —
 * daraus kommt der Chat, gegen den die Allowlist geprüft wird; {@code data} trägt die Callback-Daten.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramCallbackQuery(String id, TelegramUser from, TelegramMessage message, String data) {
}
