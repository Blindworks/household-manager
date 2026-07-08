package com.household.manager.entitystate;

/**
 * Domain einer Entität (Home-Assistant-Stil). Bestimmt das Präfix der Entity-ID.
 */
public enum EntityDomain {
    SWITCH,
    SENSOR,
    BINARY_SENSOR;

    /** Präfix für Entity-IDs, z. B. "sensor" oder "binary_sensor". */
    public String idPrefix() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
