package com.household.manager.ai;

/** Fehler der Claude-API (Transport oder leere Antwort). */
public class AiException extends RuntimeException {
    public AiException(String message, Throwable cause) {
        super(message, cause);
    }
}
