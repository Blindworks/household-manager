package com.household.manager.blaulicht.source;

/** Eine Quelle war nicht lesbar (Verbindung, HTTP-Status, Format). Der Text geht auf die Job-Tafel. */
public class BlaulichtFetchException extends Exception {

    public BlaulichtFetchException(String message) {
        super(message);
    }

    public BlaulichtFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
