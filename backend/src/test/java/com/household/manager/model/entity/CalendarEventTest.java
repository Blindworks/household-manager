package com.household.manager.model.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarEventTest {

    private CalendarEvent event() {
        return CalendarEvent.builder()
                .title("Testtermin")
                .categoryId(1L)
                .startDate(LocalDate.of(2026, 1, 1))
                .build();
    }

    @Test
    void exdateSetIstLeerWennFeldNullIst() {
        CalendarEvent event = event();
        event.setExdates(null);

        assertThat(event.exdateSet()).isEmpty();
    }

    @Test
    void exdateSetIstLeerWennFeldBlankIst() {
        CalendarEvent event = event();
        event.setExdates("   ");

        assertThat(event.exdateSet()).isEmpty();
    }

    @Test
    void exdateSetParstMehrereDatenUndToleriertLeerzeichen() {
        CalendarEvent event = event();
        event.setExdates(" 2026-01-01 , 2026-02-15 ,2026-03-20");

        assertThat(event.exdateSet()).containsExactlyInAnyOrder(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 2, 15),
                LocalDate.of(2026, 3, 20));
    }

    @Test
    void exdateSetIgnoriertLeereTokensAusNachlaufendemOderDoppeltemKomma() {
        CalendarEvent event = event();
        event.setExdates("2026-01-01,,2026-02-01,");

        assertThat(event.exdateSet()).containsExactlyInAnyOrder(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 2, 1));
    }

    @Test
    void addExdateAufLeererEntitySetztGenauDiesesDatum() {
        CalendarEvent event = event();

        event.addExdate(LocalDate.of(2026, 5, 10));

        assertThat(event.exdateSet()).containsExactly(LocalDate.of(2026, 5, 10));
    }

    @Test
    void addExdateHaengtAnUndHaeltDieListeAufsteigendSortiert() {
        CalendarEvent event = event();

        event.addExdate(LocalDate.of(2026, 6, 1));
        event.addExdate(LocalDate.of(2026, 1, 1));
        event.addExdate(LocalDate.of(2026, 3, 15));

        assertThat(event.getExdates()).isEqualTo("2026-01-01,2026-03-15,2026-06-01");
    }

    @Test
    void addExdateMitBereitsVorhandenemDatumErzeugtKeinDuplikat() {
        CalendarEvent event = event();

        event.addExdate(LocalDate.of(2026, 4, 4));
        event.addExdate(LocalDate.of(2026, 4, 4));

        assertThat(event.exdateSet()).containsExactly(LocalDate.of(2026, 4, 4));
    }

    @Test
    void roundTripNachMehrerenAddExdateAufrufenEnthaeltGenauDieErwartetenDaten() {
        CalendarEvent event = event();

        event.addExdate(LocalDate.of(2026, 2, 1));
        event.addExdate(LocalDate.of(2026, 2, 8));
        event.addExdate(LocalDate.of(2026, 2, 1));
        event.addExdate(LocalDate.of(2026, 2, 15));

        assertThat(event.exdateSet()).containsExactlyInAnyOrder(
                LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 2, 8),
                LocalDate.of(2026, 2, 15));
    }

    @Test
    void removeExdateEntferntEinVorhandenesDatum() {
        CalendarEvent event = event();
        event.addExdate(LocalDate.of(2026, 4, 1));
        event.addExdate(LocalDate.of(2026, 4, 8));

        event.removeExdate(LocalDate.of(2026, 4, 1));

        assertThat(event.exdateSet()).containsExactly(LocalDate.of(2026, 4, 8));
    }

    @Test
    void removeExdateLaesstAndereDatenStehen() {
        CalendarEvent event = event();
        event.addExdate(LocalDate.of(2026, 4, 1));
        event.addExdate(LocalDate.of(2026, 4, 8));
        event.addExdate(LocalDate.of(2026, 4, 15));

        event.removeExdate(LocalDate.of(2026, 4, 8));

        assertThat(event.exdateSet()).containsExactlyInAnyOrder(
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 15));
    }

    @Test
    void removeExdateIstWirkungslosWennDasDatumNichtEnthaltenIst() {
        CalendarEvent event = event();
        event.addExdate(LocalDate.of(2026, 4, 1));

        event.removeExdate(LocalDate.of(2026, 5, 1));

        assertThat(event.exdateSet()).containsExactly(LocalDate.of(2026, 4, 1));
    }

    @Test
    void removeExdateSetztExdatesAufNullWennNichtsUebrigBleibt() {
        CalendarEvent event = event();
        event.addExdate(LocalDate.of(2026, 4, 1));

        event.removeExdate(LocalDate.of(2026, 4, 1));

        assertThat(event.getExdates()).isNull();
        assertThat(event.exdateSet()).isEmpty();
    }
}
