package com.household.manager.charging;

/** Die Ladesaeulen-Quelle hat nicht oder unbrauchbar geantwortet (wird zu 502). */
public class ChargingSourceException extends RuntimeException {
    public ChargingSourceException(String message) {
        super(message);
    }

    public ChargingSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
