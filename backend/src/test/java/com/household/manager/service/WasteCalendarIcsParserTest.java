package com.household.manager.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WasteCalendarIcsParserTest {

    private final WasteCalendarIcsParser parser = new WasteCalendarIcsParser();

    private static final LocalDate FROM = LocalDate.of(2026, 7, 16);
    private static final LocalDate TO = LocalDate.of(2027, 7, 16);

    private String fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/waste/" + name)) {
            assertThat(in).as("Fixture /waste/%s muss existieren", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void liestEinzelterminMitDatumUndBezeichnung() throws IOException {
        List<ParsedWasteEvent> events = parser.parse(fixture("single-event.ics"), FROM, TO);

        assertThat(events).containsExactly(
                new ParsedWasteEvent(LocalDate.of(2026, 7, 20), "Biotonne"));
    }

    @Test
    void loestSerienterminUeberDasFensterAuf() throws IOException {
        List<ParsedWasteEvent> events = parser.parse(
                fixture("recurring-event.ics"), FROM, LocalDate.of(2026, 8, 31));

        assertThat(events).extracting(ParsedWasteEvent::date).containsExactly(
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 8, 3),
                LocalDate.of(2026, 8, 17),
                LocalDate.of(2026, 8, 31));
        assertThat(events).extracting(ParsedWasteEvent::label).containsOnly("Restmuell");
    }

    @Test
    void liefertMehrereTermineAmSelbenTag() throws IOException {
        List<ParsedWasteEvent> events = parser.parse(fixture("same-day-events.ics"), FROM, TO);

        assertThat(events).containsExactlyInAnyOrder(
                new ParsedWasteEvent(LocalDate.of(2026, 7, 20), "Biotonne"),
                new ParsedWasteEvent(LocalDate.of(2026, 7, 20), "Gelber Sack"));
    }

    @Test
    void filtertTermineVorDemFenster() throws IOException {
        List<ParsedWasteEvent> events = parser.parse(fixture("past-event.ics"), FROM, TO);

        assertThat(events).isEmpty();
    }

    @Test
    void wirftBeiInhaltDerKeinIcsIst() {
        assertThatThrownBy(() -> parser.parse("<html>Fehlerseite</html>", FROM, TO))
                .isInstanceOf(WasteCalendarException.class)
                .hasMessageContaining("Kalender");
    }
}
