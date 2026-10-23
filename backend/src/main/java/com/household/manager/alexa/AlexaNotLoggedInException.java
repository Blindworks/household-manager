package com.household.manager.alexa;

/** Der Alexa-Sidecar antwortet 409: es ist kein Amazon-Konto angemeldet (Dauerzustand ohne Einrichtung). */
public class AlexaNotLoggedInException extends AlexaException {

    public AlexaNotLoggedInException(String message) {
        super(message);
    }
}
