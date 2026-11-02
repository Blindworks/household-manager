package com.household.manager.charging.forecast;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Vorhersage für eine Station. Bei {@link Status#INSUFFICIENT_DATA} sind Wahrscheinlichkeiten und
 * Punkte leer und die Zeiten {@code null}; die Heatmap ist trotzdem gesetzt.
 *
 * @param freeProbabilityPercent Horizont in Minuten → Wahrscheinlichkeit in ganzen Prozent, dass
 *                               dann mindestens ein Ladepunkt frei ist
 * @param expectedFreeAt         frühester Median über die belegten Punkte, {@code null} sobald einer frei ist
 * @param expectedFreeLatest     frühestes 80-%-Quantil über die belegten Punkte, ebenso
 */
public record ChargingForecast(Status status, long observedDays, Map<Integer, Integer> freeProbabilityPercent,
                               List<PointForecast> points, Instant expectedFreeAt, Instant expectedFreeLatest,
                               Double[][] heatmap) {

    public enum Status { OK, INSUFFICIENT_DATA }

    /** Restdauer eines belegten Ladepunkts; Zeiten {@code null}, wenn es keine Vergleichsmenge gibt. */
    public record PointForecast(String chargePointId, Instant occupiedSince, Instant expectedFreeAt,
                                Instant expectedFreeLatest) {
    }
}
