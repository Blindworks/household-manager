package com.household.manager.tapo.exception;

public class TapoCommunicationException extends TapoConnectionException {

    public TapoCommunicationException(String message) {
        super(message);
    }

    public TapoCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
