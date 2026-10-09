package com.household.manager.tapo.hub;

import java.util.List;

/**
 * Ergebnis des direkten Probens eines Hubs per IP: die vom Hub selbst gemeldete
 * {@code device_id} (fuer die Identitaetspruefung) und seine Thermostate.
 */
public record HubProbeResult(String hubDeviceId, List<ThermostatReading> thermostats) {
}
