package com.household.manager.sun;

import java.util.Locale;

/**
 * Tagesphase, wie sie {@code sensor.sun} als State meldet. Halboffene Fenster,
 * Regel in {@link SunDayTimes#phaseAt}.
 */
public enum SunPhase {
    DAY,
    DUSK,
    NIGHT,
    DAWN;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
