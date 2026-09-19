package com.household.manager.charging;

import com.household.manager.exception.TooManyRequestsException;

/** Die Quelle hat 429 gemeldet oder der Mindestabstand des erzwungenen Abrufs ist unterschritten. */
public class ChargingRateLimitException extends TooManyRequestsException {
    public ChargingRateLimitException(String message) {
        super(message);
    }
}
