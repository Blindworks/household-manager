package com.household.manager.laundry;

/** Antwort oder Aktion ohne passende offene Frage bzw. passenden Waschgang (z. B. Doppelklick). */
public class LaundryConflictException extends RuntimeException {

    public LaundryConflictException(String message) {
        super(message);
    }
}
