package com.household.manager.exception;

/**
 * Wird ausgelöst, wenn eine Entität mit derselben Entity-ID bereits existiert
 * (z. B. beim Anlegen eines manuellen Helfers mit einem bereits vergebenen Namen).
 */
public class DuplicateEntityException extends RuntimeException {

    public DuplicateEntityException(String message) {
        super(message);
    }
}
