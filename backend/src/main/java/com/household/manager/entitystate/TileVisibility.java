package com.household.manager.entitystate;

import java.util.Locale;
import java.util.Optional;

/**
 * Sichtbarkeitsregel einer Entität auf einer Dashboard-Kachel.
 * {@code AUTO} ist der Standard und wird nie persistiert (kein Eintrag = AUTO).
 */
public enum TileVisibility {

    /** Immer auf der Kachel anzeigen (gepinnt). */
    ALWAYS,
    /** Standard: nutzungsbasierte Platzvergabe wie bisher. */
    AUTO,
    /** Nur anzeigen, solange der Zustand "on" ist (z. B. fertige Waschmaschine). */
    WHEN_ON,
    /** Nie auf der Kachel anzeigen. */
    NEVER;

    /** Case-insensitives Parsen; leer bei unbekanntem Wert. */
    public static Optional<TileVisibility> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
