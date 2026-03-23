package com.household.manager.shelly;

public class ShellyException extends RuntimeException {

    public ShellyException(String message) {
        super(message);
    }

    public ShellyException(String message, Throwable cause) {
        super(message, cause);
    }
}
