package com.household.manager.alexa;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/** Fluechtige, im Speicher gehaltene Alexa-Sitzung (nicht persistiert). */
@Getter
@Builder
public class AlexaSession {

    /** Cookie-Header-Wert fuer alexa.<domain> (Name=Wert; ...). */
    private final String cookie;

    /** CSRF-Token fuer schreibende Aufrufe. */
    private final String csrf;

    /** Aktuelles Access-Token. */
    private final String accessToken;

    /** Amazon-Kundennummer (fuer behaviors/preview). */
    private final String customerId;

    /** Zeitpunkt, ab dem die Sitzung als abgelaufen gilt. */
    private final Instant expiresAt;

    public boolean isValid() {
        return cookie != null && csrf != null && customerId != null
                && expiresAt != null && Instant.now().isBefore(expiresAt);
    }
}
