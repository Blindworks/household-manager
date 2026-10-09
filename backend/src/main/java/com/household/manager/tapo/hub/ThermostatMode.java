package com.household.manager.tapo.hub;

/**
 * Betriebsart eines Thermostats. Das Geraet kennt kein echtes "Aus": {@link #OFF} ist
 * Zieltemperatur 5 Grad ohne Frostschutz-Flag.
 */
public enum ThermostatMode {
    MANUAL,
    FROST_PROTECTION,
    OFF
}
