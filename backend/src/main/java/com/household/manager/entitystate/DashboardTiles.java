package com.household.manager.entitystate;

import java.util.Set;

/**
 * Stabile Schlüssel der Dashboard-Kacheln, für die Sichtbarkeitsregeln
 * gepflegt werden können. Unbekannte Keys lehnt die API ab.
 */
public final class DashboardTiles {

    /** Schalter-Kachel des Dashboards. */
    public static final String SWITCHES = "switches";

    private static final Set<String> KNOWN = Set.of(SWITCHES);

    private DashboardTiles() {
    }

    public static boolean isKnown(String tileKey) {
        return tileKey != null && KNOWN.contains(tileKey);
    }
}
