package com.household.manager.calendar;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecurrenceExpansionServiceTest {

    private final RecurrenceExpansionService service = new RecurrenceExpansionService();

    @Test
    void taeglicheSerieLiefertJedenTagImFenster() {
        List<LocalDate> result = service.expand("FREQ=DAILY", LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 12));
        assertThat(result).containsExactly(
                LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 11), LocalDate.of(2026, 7, 12));
    }

    @Test
    void woechentlicheSerieMitBydayTrifftNurDienstage() {
        // 07.07.2026 ist ein Dienstag
        List<LocalDate> result = service.expand("FREQ=WEEKLY;BYDAY=TU", LocalDate.of(2026, 7, 7),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        assertThat(result).containsExactly(
                LocalDate.of(2026, 7, 7), LocalDate.of(2026, 7, 14),
                LocalDate.of(2026, 7, 21), LocalDate.of(2026, 7, 28));
    }

    @Test
    void jederZweiteDienstagImMonat() {
        List<LocalDate> result = service.expand("FREQ=MONTHLY;BYDAY=2TU", LocalDate.of(2026, 7, 14),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30));
        assertThat(result).containsExactly(
                LocalDate.of(2026, 7, 14), LocalDate.of(2026, 8, 11), LocalDate.of(2026, 9, 8));
    }

    @Test
    void countBegrenztDieSerie() {
        List<LocalDate> result = service.expand("FREQ=DAILY;COUNT=3", LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        assertThat(result).hasSize(3);
    }

    @Test
    void untilBegrenztDieSerie() {
        List<LocalDate> result = service.expand("FREQ=DAILY;UNTIL=20260703", LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        assertThat(result).containsExactly(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 3));
    }

    @Test
    void fensterVorSerienstartIstLeer() {
        List<LocalDate> result = service.expand("FREQ=DAILY", LocalDate.of(2026, 7, 10),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 5));
        assertThat(result).isEmpty();
    }

    @Test
    void expansionIstAufMaxOccurrencesGekappt() {
        List<LocalDate> result = service.expand("FREQ=DAILY", LocalDate.of(2020, 1, 1),
                LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1));
        assertThat(result).hasSize(RecurrenceExpansionService.MAX_OCCURRENCES);
    }

    @Test
    void ungueltigeRegelWirdMitBadRequestAbgelehnt() {
        assertThatThrownBy(() -> service.validate("FREQ=BANANA"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void leereOderFehlendeRegelWirdMitBadRequestAbgelehnt() {
        assertThatThrownBy(() -> service.validate(null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.validate("")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.validate("   ")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void fensterEndeVorFensterstartIstLeer() {
        List<LocalDate> result = service.expand("FREQ=DAILY", LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 5));
        assertThat(result).isEmpty();
    }
}
