package com.household.manager.exception;

/**
 * Ein manueller Trigger wurde abgelehnt, weil entweder bereits ein gleichartiger Lauf aktiv ist
 * (Ueberlappungs-Schutz, siehe {@code PresencePollingService.refreshNow}) oder eine Cooldown-Frist
 * seit dem letzten Lauf noch nicht abgelaufen ist (siehe {@code NetworkSpeedtestService.reserveManualSlot}).
 * Eigener Typ (statt {@code IllegalStateException}), damit der {@code GlobalExceptionHandler} gezielt
 * auf 429 statt 400 abbilden kann (Muster: {@code TractiveRateLimitException}).
 */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
