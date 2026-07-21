package com.household.manager.nuki;

/** Fehler bei der Kommunikation mit der Nuki Web API. */
public class NukiException extends RuntimeException {

    public NukiException(String message, Throwable cause) {
        super(message, cause);
    }
}
