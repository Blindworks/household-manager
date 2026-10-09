package com.household.manager.tapo.hub;

import java.util.List;

/**
 * Lokale Anbindung eines Tapo-Hubs (H100) und seiner Thermostat-Kindgeraete (KE100).
 */
public interface TapoHubClient {

    /** Ein Aufruf: alle Thermostat-Kinder des Hubs. */
    List<ThermostatReading> readThermostats(String hubIp);

    void setTargetTemp(String hubIp, String childDeviceId, double targetTemp);

    void setFrostProtection(String hubIp, String childDeviceId, boolean on);

    /**
     * Probt den Hub direkt per IP (ohne Verbindungscache) und liefert seine Identitaet und
     * Thermostate. Wirft {@link com.household.manager.tapo.TapoException}, wenn der Hub weder
     * ueber KLAP noch ueber AES antwortet.
     */
    HubProbeResult probeHub(String hubIp);
}
