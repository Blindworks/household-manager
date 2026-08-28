package com.household.manager.petsupply;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Berechnet die Fuetterungszeitpunkte (taeglich 7:00 und 16:00 Wandzeit) in einem
 * Instant-Fenster. Instants statt Wandzeit, weil die Berliner Wandzeit bei der
 * Oktober-Zeitumstellung nicht monoton ist (siehe CalendarReminderScheduler);
 * 7:00 und 16:00 existieren an jedem Berliner Tag genau einmal, die Aufloesung
 * per atZone ist damit eindeutig.
 */
final class FeedingSchedule {

    static final List<LocalTime> FEEDING_TIMES = List.of(LocalTime.of(7, 0), LocalTime.of(16, 0));

    private FeedingSchedule() {
    }

    /** Alle Fuetterungszeitpunkte in (sinceExclusive, untilInclusive], aufsteigend. */
    static List<Instant> between(Instant sinceExclusive, Instant untilInclusive, ZoneId zone) {
        List<Instant> due = new ArrayList<>();
        LocalDate day = sinceExclusive.atZone(zone).toLocalDate();
        LocalDate lastDay = untilInclusive.atZone(zone).toLocalDate();
        while (!day.isAfter(lastDay)) {
            for (LocalTime time : FEEDING_TIMES) {
                Instant feeding = day.atTime(time).atZone(zone).toInstant();
                if (feeding.isAfter(sinceExclusive) && !feeding.isAfter(untilInclusive)) {
                    due.add(feeding);
                }
            }
            day = day.plusDays(1);
        }
        return due;
    }
}
