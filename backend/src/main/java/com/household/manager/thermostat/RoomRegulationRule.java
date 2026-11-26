package com.household.manager.thermostat;

/**
 * Einzige Definition, auf welchen Sollwert die Raumregelung ein Thermostat stellt.
 *
 * <p>Der Fuehler des KE100 sitzt am Heizkoerper. Das Thermostat regelt selbst auf diesen Fuehler:
 * es oeffnet das Ventil nur, wenn sein eigener Messwert deutlich unter seinem Sollwert liegt. Die
 * Regel stellt den Sollwert deshalb relativ zum Messwert am Heizkoerper ein und verstaerkt die
 * Abweichung im Raum: Soll = Heizkoerper-Wert + {@link #GAIN} x (Raum-Soll - Raumwert). Ist der
 * Raum zu kalt, liegt der Sollwert klar ueber dem Heizkoerper-Wert und das Ventil oeffnet; ist er
 * zu warm, liegt er darunter und das Ventil schliesst.
 *
 * <p>Die erste Fassung (Soll = Raum-Soll + (Heizkoerper - Raum)) gab dem Thermostat nur genau die
 * Raumabweichung als Antrieb, also oft nur ein Grad. Das reichte nicht ueber die Hysterese des
 * KE100: real 2026-11-26 im Buero, Raum 19,8 statt 21, Heizkoerper 17, das Ventil blieb zu. Dazu
 * hob die feste Untergrenze (Raum-Soll - 2) den Sollwert bei kaltem Heizkoerper auch dann ueber
 * dessen Messwert, wenn der Raum schon zu warm war.
 *
 * <p>Der Antrieb wird auf {@link #MAX_DRIVE} um den Heizkoerper-Wert begrenzt, damit ein
 * ausreissender Raumwert (Fuehler in der Sonne, am offenen Fenster) das Thermostat nicht auf 30
 * Grad oder ins Aus treibt.
 */
public final class RoomRegulationRule {

    /** Verstaerkung der Raumabweichung (Startschaetzung, im Verlauf pruefen). */
    static final double GAIN = 3.0;
    /** Hoechstens so weit weicht der Sollwert vom Messwert am Heizkoerper ab. */
    static final double MAX_DRIVE = 5.0;
    /** Nie 5,0 oder weniger: das wertet das Projekt als OFF (siehe ThermostatService). */
    static final double MIN_SETPOINT = 5.5;
    static final double MAX_SETPOINT = 30.0;

    private RoomRegulationRule() {
    }

    /** @return Sollwert fuer das Thermostat, auf 0,5 gerundet und innerhalb der Grenzen */
    public static double setpoint(double roomTarget, double roomTemp, double thermostatTemp) {
        double drive = GAIN * (roomTarget - roomTemp);
        double raw = thermostatTemp + Math.max(-MAX_DRIVE, Math.min(MAX_DRIVE, drive));
        double rounded = Math.round(raw * 2.0) / 2.0;
        return Math.max(MIN_SETPOINT, Math.min(MAX_SETPOINT, rounded));
    }
}
