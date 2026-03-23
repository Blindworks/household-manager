package com.household.manager.tapo;

public class TapoException extends RuntimeException {

    public TapoException(String message) {
        super(message);
    }

    public TapoException(String message, Throwable cause) {
        super(message, cause);
    }
}
