package com.household.manager.telegram;

/** Laufzeitfehler der Telegram-Integration. Meldungstexte dürfen nie den Bot-Token enthalten. */
public class TelegramException extends RuntimeException {

    public TelegramException(String message, Throwable cause) {
        super(message, cause);
    }
}
