package com.household.manager.presence;

/** Ergebnis einer Handy-Probe: hat der Host irgendwie geantwortet? */
public enum ProbeResult {
    /** Verbindung angenommen ODER abgelehnt (RST) — der Host lebt. */
    RESPONDED,
    /** Timeout auf allen Ports — keine Lebensaeusserung. */
    SILENT
}
