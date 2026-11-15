package com.household.manager.grocery.source;

/** Abruf einer Quelle gescheitert; die Nachricht ist der Klartext-Grund für die Admin-Seite. */
public class SourceFetchException extends Exception {

    public SourceFetchException(String message) {
        super(message);
    }

    public SourceFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
