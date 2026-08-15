package com.household.manager.petfood;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeedingScheduleTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, BERLIN).toInstant();
    }

    @Test
    void leeresFensterLiefertNichts() {
        List<Instant> due = FeedingSchedule.between(
                at(2026, 8, 15, 8, 0), at(2026, 8, 15, 15, 0), BERLIN);
        assertThat(due).isEmpty();
    }

    @Test
    void einMinutenFensterUmSiebenLiefertGenauDieMorgenfuetterung() {
        List<Instant> due = FeedingSchedule.between(
                at(2026, 8, 15, 6, 59), at(2026, 8, 15, 7, 0), BERLIN);
        assertThat(due).containsExactly(at(2026, 8, 15, 7, 0));
    }

    @Test
    void untergrenzeIstExklusiv() {
        // Marke exakt auf 7:00: diese Fuetterung ist schon verbucht.
        List<Instant> due = FeedingSchedule.between(
                at(2026, 8, 15, 7, 0), at(2026, 8, 15, 7, 30), BERLIN);
        assertThat(due).isEmpty();
    }

    @Test
    void mehrtagesFensterHoltAlleFuetterungenNach() {
        // 25 Stunden Stillstand ueber Nacht: 16:00 (Tag 1), 7:00 und 16:00 (Tag 2).
        List<Instant> due = FeedingSchedule.between(
                at(2026, 8, 14, 15, 0), at(2026, 8, 15, 16, 0), BERLIN);
        assertThat(due).containsExactly(
                at(2026, 8, 14, 16, 0),
                at(2026, 8, 15, 7, 0),
                at(2026, 8, 15, 16, 0));
    }

    @Test
    void zeitumstellungOktoberLiefertProTagWeiterGenauZweiFuetterungen() {
        // 2026-10-25: Ende der Sommerzeit in Europa (03:00 -> 02:00). Das Fenster
        // ueberspannt den Rueckstellmoment; 7:00/16:00 muessen trotzdem genau einmal kommen.
        List<Instant> due = FeedingSchedule.between(
                at(2026, 10, 24, 20, 0), at(2026, 10, 25, 20, 0), BERLIN);
        assertThat(due).containsExactly(
                at(2026, 10, 25, 7, 0),
                at(2026, 10, 25, 16, 0));
    }
}
