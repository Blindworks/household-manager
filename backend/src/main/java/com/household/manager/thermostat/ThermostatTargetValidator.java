package com.household.manager.thermostat;

/**
 * Einzige Definition der erlaubten Solltemperaturen: 5,0 bis 30,0 Grad in 0,5er-Schritten.
 */
public final class ThermostatTargetValidator {

    public static final double MIN_TARGET = 5.0;
    public static final double MAX_TARGET = 30.0;

    private ThermostatTargetValidator() {
    }

    /**
     * @throws IllegalArgumentException (-> 400) bei nicht endlichen, ausserhalb des Bereichs
     *                                  liegenden oder nicht auf dem 0,5-Raster liegenden Werten
     */
    public static void validate(double value) {
        // isFinite ist hier tragend: NaN besteht jeden einseitigen Vergleich (alle sind false).
        if (!Double.isFinite(value) || value < MIN_TARGET || value > MAX_TARGET || value * 2 != Math.rint(value * 2)) {
            throw new IllegalArgumentException("Solltemperatur muss zwischen " + MIN_TARGET + " und "
                    + MAX_TARGET + " Grad in 0,5-Schritten liegen.");
        }
    }
}
