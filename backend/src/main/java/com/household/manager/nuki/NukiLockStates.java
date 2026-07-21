package com.household.manager.nuki;

/** Übersetzt die numerischen Nuki-Zustandscodes in Entity-States. */
public final class NukiLockStates {

    public static final String UNKNOWN = "unknown";

    private NukiLockStates() {
    }

    /** Lock-State-Code → Entity-State (z. B. 1 → "locked"). */
    public static String lockState(Integer code) {
        if (code == null) {
            return UNKNOWN;
        }
        return switch (code) {
            case 0 -> "uncalibrated";
            case 1 -> "locked";
            case 2 -> "unlocking";
            case 3, 6 -> "unlocked";
            case 4 -> "locking";
            case 5 -> "unlatched";
            case 7 -> "unlatching";
            case 254 -> "jammed";
            default -> UNKNOWN;
        };
    }

    /**
     * Door-State-Code → binary_sensor-State mit on=offen-Semantik,
     * oder null wenn kein Türsensor vorhanden/aktiv ist (Code 0/1/fehlend).
     */
    public static String doorState(Integer code) {
        if (code == null || code == 0 || code == 1) {
            return null;
        }
        return switch (code) {
            case 2 -> "off";
            case 3 -> "on";
            default -> UNKNOWN;
        };
    }
}
