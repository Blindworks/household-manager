package com.household.manager.dirigera.client;

import com.household.manager.dirigera.DirigeraException;

/** Der Hub hat das Token abgelehnt (HTTP 401), z. B. weil es in der IKEA-App widerrufen wurde. */
public class DirigeraUnauthorizedException extends DirigeraException {

    public DirigeraUnauthorizedException(String message) {
        super(message);
    }
}
