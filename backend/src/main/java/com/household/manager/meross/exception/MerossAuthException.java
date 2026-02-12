package com.household.manager.meross.exception;

public class MerossAuthException extends MerossException {

    public MerossAuthException(String message) {
        super(message);
    }

    public MerossAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
