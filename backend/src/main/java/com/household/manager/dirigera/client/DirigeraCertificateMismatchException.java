package com.household.manager.dirigera.client;

import com.household.manager.dirigera.DirigeraException;

/** Das Zertifikat unter der Hub-Adresse passt nicht zum beim Koppeln gemerkten Fingerabdruck. */
public class DirigeraCertificateMismatchException extends DirigeraException {

    public DirigeraCertificateMismatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
