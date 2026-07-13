package com.household.manager.entitystate;

/**
 * Domain einer Entität (Home-Assistant-Stil). Bestimmt das Präfix der Entity-ID.
 */
public enum EntityDomain {
    SWITCH,
    SENSOR,
    BINARY_SENSOR,
    /** Vom Benutzer angelegter, manuell schaltbarer Boolean-Helfer (z. B. "Nachtmodus"). */
    INPUT_BOOLEAN,
    /** Vom Benutzer angelegter numerischer Helfer (optional mit min/max/step/unit). */
    INPUT_NUMBER,
    /** Vom Benutzer angelegter Freitext-Helfer. */
    INPUT_TEXT,
    /** Vom Benutzer angelegter Auswahl-Helfer (Zustand ist eine der hinterlegten Optionen). */
    INPUT_SELECT;

    /** Ob es sich um einen manuell anlegbaren Helfer-Typ handelt. */
    public boolean isManualHelper() {
        return this == INPUT_BOOLEAN || this == INPUT_NUMBER || this == INPUT_TEXT || this == INPUT_SELECT;
    }

    /** Präfix für Entity-IDs, z. B. "sensor" oder "binary_sensor". */
    public String idPrefix() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
