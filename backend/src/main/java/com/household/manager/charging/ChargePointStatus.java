package com.household.manager.charging;

/** Zustand eines einzelnen Ladepunkts. Unbekannte Quelltexte werden UNKNOWN, nie FREE. */
public enum ChargePointStatus {
    FREE, OCCUPIED, OUT_OF_SERVICE, UNKNOWN
}
