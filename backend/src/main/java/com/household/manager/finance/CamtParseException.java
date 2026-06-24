package com.household.manager.finance;

/** Thrown when a CAMT document cannot be parsed (not CAMT, malformed XML, etc.). */
public class CamtParseException extends RuntimeException {
    public CamtParseException(String message, Throwable cause) {
        super(message, cause);
    }

    public CamtParseException(String message) {
        super(message);
    }
}
