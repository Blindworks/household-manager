package com.household.manager.grocery.source;

/** HTTP-Antwort mit Fehlerstatus; der Status erlaubt Quellen gezielte Reaktionen (z. B. Schlüssel erneuern bei 401). */
public class HttpStatusException extends SourceFetchException {

    private final int status;

    public HttpStatusException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
