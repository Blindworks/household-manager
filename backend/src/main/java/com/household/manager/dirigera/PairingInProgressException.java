package com.household.manager.dirigera;

/** Ein Kopplungsvorgang laeuft bereits (wird zu 409). */
public class PairingInProgressException extends RuntimeException {

    public PairingInProgressException(String message) {
        super(message);
    }
}
