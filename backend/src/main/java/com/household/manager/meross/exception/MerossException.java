package com.household.manager.meross.exception;

public class MerossException extends RuntimeException {

    public MerossException(String message) {
        super(message);
    }

    public MerossException(String message, Throwable cause) {
        super(message, cause);
    }
}
