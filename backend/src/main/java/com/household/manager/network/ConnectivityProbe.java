package com.household.manager.network;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Prueft die Erreichbarkeit eines HTTP-Ziels und misst die Antwortzeit.
 */
public interface ConnectivityProbe {

    /**
     * @return die gemessene Antwortzeit, wenn das Ziel erreichbar war (jeder HTTP-Statuscode
     *         zaehlt), sonst {@link Optional#empty()} (Timeout, Verbindungsfehler o. Ae.)
     */
    Optional<Duration> probe(URI target, Duration timeout);
}
