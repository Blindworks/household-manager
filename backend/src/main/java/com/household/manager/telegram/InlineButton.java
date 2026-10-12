package com.household.manager.telegram;

/**
 * Ein Inline-Knopf unter einer Telegram-Nachricht. {@code callbackData} darf laut Bot-API höchstens
 * 64 Byte lang sein — das Format {@code agent:<id>:<decision>} bleibt weit darunter.
 */
public record InlineButton(String text, String callbackData) {
}
