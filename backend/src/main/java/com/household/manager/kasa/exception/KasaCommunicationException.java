package com.household.manager.kasa.exception;

public class KasaCommunicationException extends RuntimeException {

    public KasaCommunicationException(String message) {
        super(message);
    }

    public KasaCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
