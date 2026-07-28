package com.household.manager.tractive;

/**
 * Tractive hat das Rate-Limit einer Ressource gemeldet (HTTP 429, Code 4006).
 * Eigener Typ, damit Aufrufer weitere Requests sofort einstellen koennen,
 * statt das Limit mit Folge-Aufrufen weiter hochzuschaukeln.
 */
public class TractiveRateLimitException extends TractiveException {

    public TractiveRateLimitException(String message) {
        super(message);
    }
}
