package com.household.manager.blink;

/** Fehler der Blink-Kamera-Anbindung (Sidecar nicht erreichbar, Blink-Cloud-Fehler). */
public class BlinkException extends RuntimeException {

    public BlinkException(String message) {
        super(message);
    }

    public BlinkException(String message, Throwable cause) {
        super(message, cause);
    }
}
