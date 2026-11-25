package com.household.manager.thermostat;

/**
 * Einzige Definition, auf welchen Sollwert die Raumregelung ein Thermostat stellt.
 *
 * <p>Der Fuehler des KE100 sitzt am Heizkoerper und misst beim Heizen deutlich mehr als im Raum.
 * Die Regel verschiebt deshalb den Sollwert um genau diesen Versatz: Soll = Raum-Soll + (Wert am
 * Heizkoerper - Wert im Raum). Ist der Raum zu kalt, liegt der Heizkoerper-Wert unter dem neuen Soll
 * und das Ventil oeffnet; ist er zu warm, schliesst es. Dasselbe Prinzip nutzen gaengige
 * Heim-Automationen fuer Heizkoerper-Thermostate ("externer Fuehler als Kalibrierung").
 *
 * <p>Der Versatz wird begrenzt, damit ein ausreissender Raumwert (Fuehler in der Sonne, am offenen
 * Fenster) das Thermostat nicht auf 30 Grad oder ins Aus treibt.
 */
public final class RoomRegulationRule {

    /** Hoechstens so viel unter dem Raum-Soll. */
    static final double MAX_BELOW = 2.0;
    /** Hoechstens so viel ueber dem Raum-Soll (Heizkoerper misst beim Heizen oft 3-4 Grad mehr). */
    static final double MAX_ABOVE = 5.0;
    /** Nie 5,0 oder weniger: das wertet das Projekt als OFF (siehe ThermostatService). */
    static final double MIN_SETPOINT = 5.5;
    static final double MAX_SETPOINT = 30.0;

    private RoomRegulationRule() {
    }

    /** @return Sollwert fuer das Thermostat, auf 0,5 gerundet und innerhalb der Grenzen */
    public static double setpoint(double roomTarget, double roomTemp, double thermostatTemp) {
        double offset = thermostatTemp - roomTemp;
        double raw = roomTarget + Math.max(-MAX_BELOW, Math.min(MAX_ABOVE, offset));
        double rounded = Math.round(raw * 2.0) / 2.0;
        return Math.max(MIN_SETPOINT, Math.min(MAX_SETPOINT, rounded));
    }
}
