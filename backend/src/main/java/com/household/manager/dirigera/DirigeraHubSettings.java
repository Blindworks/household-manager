package com.household.manager.dirigera;

/** Verbindungsdaten des Dirigera-Hubs; {@code null} heisst "nicht gesetzt". */
public record DirigeraHubSettings(String host, String accessToken, String certSha256, String hubId) {

    /** Gekoppelt ist der Hub erst, wenn alle vier Werte da sind. */
    public boolean isPaired() {
        return host != null && accessToken != null && certSha256 != null && hubId != null;
    }
}
