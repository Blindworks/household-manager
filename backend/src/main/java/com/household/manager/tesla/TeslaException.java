package com.household.manager.tesla;

/** Fehler bei der Kommunikation mit Tessie (wird zu 502, nie 401). */
public class TeslaException extends RuntimeException {

    public TeslaException(String message) {
        super(message);
    }

    public TeslaException(String message, Throwable cause) {
        super(message, cause);
    }
}
