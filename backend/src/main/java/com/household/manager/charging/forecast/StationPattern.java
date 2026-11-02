package com.household.manager.charging.forecast;

import java.time.Duration;
import java.util.List;

/**
 * Gelerntes Belegungsmuster einer Station.
 *
 * @param observed                  gesamte beobachtete Zeit (nicht nur das Musterfenster)
 * @param occupancy                 [Wochentag 0=Mo…6=So/Feiertag][Stunde]: Anteil belegt 0–1, {@code null} = nicht beobachtet
 * @param startsPerFreePointSecond  Belegungsbeginne je freier Ladepunkt-Sekunde, 0 wenn nicht beobachtet
 * @param overallStartRate          Rückfallwert über alle Zellen
 * @param occupancies               abgeschlossene Belegungen im Musterfenster
 */
public record StationPattern(Duration observed, Double[][] occupancy, double[][] startsPerFreePointSecond,
                             double overallStartRate, List<PastOccupancy> occupancies) {
}
