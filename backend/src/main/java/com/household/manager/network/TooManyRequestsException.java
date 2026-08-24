package com.household.manager.network;

/**
 * Ein manueller Speedtest-Trigger wurde innerhalb der Cooldown-Frist erneut angefragt.
 * Eigener Typ (statt {@code IllegalStateException}), damit der {@code GlobalExceptionHandler}
 * gezielt auf 429 statt 400 abbilden kann (Muster: {@code TractiveRateLimitException}).
 */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
