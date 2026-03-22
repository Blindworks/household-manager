package com.household.manager.ankersolix.api;

/**
 * Thrown when the Anker API returns an error code or when a network/
 * serialisation problem prevents a successful call.
 */
public class AnkerApiException extends RuntimeException {

    public AnkerApiException(String message) {
        super(message);
    }

    public AnkerApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
