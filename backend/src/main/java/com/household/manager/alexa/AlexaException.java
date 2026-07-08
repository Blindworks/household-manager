package com.household.manager.alexa;

/** Fehler bei der Kommunikation mit den (inoffiziellen) Alexa-Endpunkten. */
public class AlexaException extends RuntimeException {

    public AlexaException(String message) {
        super(message);
    }

    public AlexaException(String message, Throwable cause) {
        super(message, cause);
    }
}
