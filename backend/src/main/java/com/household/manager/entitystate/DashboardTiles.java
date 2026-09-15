package com.household.manager.entitystate;

import java.util.Set;

/**
 * Stabile Schlüssel der Dashboard-Kacheln, für die Sichtbarkeitsregeln
 * gepflegt werden können. Unbekannte Keys lehnt die API ab.
 */
public final class DashboardTiles {

    /** Schalter-Kachel des Dashboards. */
    public static final String SWITCHES = "switches";

    /**
     * Modus-Leiste des Dashboards (seit 2026-09-15 admin-konfigurierbar). AUTO = Katalog-Modi
     * (Marker {@code mode}) sichtbar, gewöhnliche Helfer nicht; ALWAYS holt einen Helfer in
     * die Leiste, NEVER nimmt einen Modus heraus, WHEN_ON zeigt ihn nur solange er an ist.
     */
    public static final String MODES = "modes";

    private static final Set<String> KNOWN = Set.of(SWITCHES, MODES);

    private DashboardTiles() {
    }

    public static boolean isKnown(String tileKey) {
        return tileKey != null && KNOWN.contains(tileKey);
    }
}
