package com.household.manager.exception;

/**
 * Exception thrown when a requested meter reading cannot be found.
 * <p>
 * This exception extends {@link ResourceNotFoundException} to provide
 * specific handling for meter reading-related not found scenarios.
 */
public class MeterReadingNotFoundException extends ResourceNotFoundException {

    /**
     * Constructs a new MeterReadingNotFoundException with the specified detail message.
     *
     * @param message the detail message explaining why the meter reading was not found
     */
    public MeterReadingNotFoundException(String message) {
        super(message);
    }

    /**
     * Constructs a new MeterReadingNotFoundException with a message and cause.
     *
     * @param message the detail message
     * @param cause the cause of the exception
     */
    public MeterReadingNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
