package com.household.manager.dirigera;

/** Fehler in der Kommunikation mit dem Dirigera-Hub oder in dessen Antwort (wird zu 502). */
public class DirigeraException extends RuntimeException {

    public DirigeraException(String message) {
        super(message);
    }

    public DirigeraException(String message, Throwable cause) {
        super(message, cause);
    }
}
