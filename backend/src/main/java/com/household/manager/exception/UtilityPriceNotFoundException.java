package com.household.manager.exception;

/**
 * Exception thrown when a utility price cannot be found.
 * This extends ResourceNotFoundException for consistent error handling.
 */
public class UtilityPriceNotFoundException extends ResourceNotFoundException {

    public UtilityPriceNotFoundException(String message) {
        super(message);
    }

    public UtilityPriceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
