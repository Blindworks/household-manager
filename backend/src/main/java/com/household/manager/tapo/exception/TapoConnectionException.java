package com.household.manager.tapo.exception;

public class TapoConnectionException extends TapoException {

    public TapoConnectionException(String message) {
        super(message);
    }

    public TapoConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
