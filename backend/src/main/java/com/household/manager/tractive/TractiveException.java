package com.household.manager.tractive;

/** Fehler beim Zugriff auf die Tractive-Cloud-API. */
public class TractiveException extends RuntimeException {

    public TractiveException(String message, Throwable cause) {
        super(message, cause);
    }

    public TractiveException(String message) {
        super(message);
    }
}
