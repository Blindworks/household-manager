package com.household.manager.laundry;

import java.util.OptionalDouble;

/** Reine Physik der Trocknungsvorhersage: Dampfdruckdefizit und Restzeit. */
public final class DryingPhysics {

    private DryingPhysics() {
    }

    /** Magnus-Formel: Saettigungsdampfdruck mal (1 - rel. Feuchte), nie negativ. */
    public static double vaporPressureDeficitKpa(double tempC, double relHumidityPercent) {
        double saturation = 0.6112 * Math.exp(17.62 * tempC / (243.12 + tempC));
        return Math.max(0.0, saturation * (1 - relHumidityPercent / 100.0));
    }

    /** Restzeit in Stunden bei konstantem Defizit; leer ohne Trockenleistung, 0 wenn erreicht. */
    public static OptionalDouble remainingHours(double requiredLoad, double accumulatedLoad, double currentVpd) {
        if (currentVpd <= 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(Math.max(0.0, (requiredLoad - accumulatedLoad) / currentVpd));
    }
}
