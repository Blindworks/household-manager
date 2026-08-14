package com.household.manager.system;

/** Fehler bei der Kommunikation mit dem Rebooter-Sidecar (wird zu HTTP 502). */
public class RebooterException extends RuntimeException {

    public RebooterException(String message) {
        super(message);
    }

    public RebooterException(String message, Throwable cause) {
        super(message, cause);
    }
}
