package com.household.manager.network;

import java.time.Duration;

/**
 * Prueft, ob ein TCP-Port erreichbar ist (reiner Connect-Test, keine Anwendungsprotokoll-Pruefung).
 */
public interface TcpPortProbe {

    /**
     * @return {@code true}, wenn sich innerhalb des Timeouts eine TCP-Verbindung aufbauen liess
     */
    boolean isOpen(String host, int port, Duration timeout);
}
