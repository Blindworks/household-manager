package com.household.manager.tractive;

/** Tractive hat die Zugangsdaten abgelehnt (im Gegensatz zu einem Transportfehler). */
public class TractiveAuthException extends TractiveException {

    public TractiveAuthException(String message) {
        super(message);
    }
}
