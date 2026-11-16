package com.household.manager.dirigera;

/**
 * Art eines Dirigera-Sensors: Thermometer (TIMMERFLOTTE), Fenster-/Tuerkontakt (z. B. MYGGBETT)
 * oder Bewegungsmelder mit Helligkeitsfuehler (MYGGSPRAY).
 */
public enum DirigeraSensorKind {
    ENVIRONMENT,
    CONTACT,
    MOTION
}
