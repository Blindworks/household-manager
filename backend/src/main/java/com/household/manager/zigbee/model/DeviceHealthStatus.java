package com.household.manager.zigbee.model;

/** Urteil ueber ein einzelnes Zigbee-Geraet, Reihenfolge = Prioritaet im Resolver. */
public enum DeviceHealthStatus {
    /** zigbee2mqtt meldet das Geraet explizit als offline. */
    OFFLINE,
    /** Interview nicht abgeschlossen (frisch angelernt oder gescheitert). */
    INTERVIEW,
    /** Nie eine Nachricht gehoert — weder seit dem Start noch in der DB. */
    UNKNOWN,
    /** Laenger still als die Schwelle seiner Stromquelle. */
    SILENT,
    ACTIVE
}
