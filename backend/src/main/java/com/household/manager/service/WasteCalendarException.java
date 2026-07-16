package com.household.manager.service;

/** Fehler beim Abrufen oder Parsen des Müllabfuhr-Kalenders. */
public class WasteCalendarException extends RuntimeException {

    public WasteCalendarException(String message) {
        super(message);
    }

    public WasteCalendarException(String message, Throwable cause) {
        super(message, cause);
    }
}
