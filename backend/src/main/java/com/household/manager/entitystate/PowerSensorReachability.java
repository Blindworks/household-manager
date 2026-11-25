package com.household.manager.entitystate;

import java.util.Map;

/**
 * Einzige Definition von „die Steckdose hinter diesem Power-Sensor ist nicht
 * erreichbar“. Quellen, die einen Ausfall bewusst nicht als {@code unavailable}
 * melden (Meross: ein Zustandswechsel ließe flankengetriggerte Flows feuern),
 * setzen stattdessen das Attribut {@value #ATTRIBUTE} auf {@code false} und lassen
 * den Zustand stehen. Verbraucher-Liste, Schalter-Watt und Modus-Check fragen hier.
 */
public final class PowerSensorReachability {

    /** Attribut am Power-Sensor; fehlt es, gilt der Sensor als erreichbar. */
    public static final String ATTRIBUTE = "reachable";
    /** Zeitpunkt, seit dem die Steckdose nicht erreichbar ist (lokale Zeit, ISO). */
    public static final String SINCE_ATTRIBUTE = "unreachableSince";

    private PowerSensorReachability() {
    }

    /** Nur ein ausdrückliches {@code false} zählt — fehlt das Attribut, wird nichts geraten. */
    public static boolean isUnreachable(Map<String, Object> attributes) {
        return attributes != null && Boolean.FALSE.equals(attributes.get(ATTRIBUTE));
    }
}
