package com.household.manager.blaulicht.processing;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class OccurredAtExtractorTest {

    private static final LocalDateTime PUBLISHED = LocalDateTime.of(2026, 11, 25, 14, 0);

    private static Occurrence extract(String text) {
        return OccurredAtExtractor.extract(text, PUBLISHED).orElse(null);
    }

    @Test
    void zeitraumUeberZweiTage() {
        assertThat(extract("Einbrecher drangen im Zeitraum von Mittwoch (21.10.2026), 08:00 Uhr bis Donnerstag "
                + "(22.10.2026), 08:00 Uhr durch ein Fenster ein."))
                .isEqualTo(new Occurrence(LocalDateTime.of(2026, 10, 21, 8, 0), LocalDateTime.of(2026, 10, 22, 8, 0)));
        assertThat(extract("Im Zeitraum von Montag, 16.11.2026, 18:00 Uhr bis Montag, 23.11.2026, 14:00 Uhr versuchten"))
                .isEqualTo(new Occurrence(LocalDateTime.of(2026, 11, 16, 18, 0), LocalDateTime.of(2026, 11, 23, 14, 0)));
    }

    @Test
    void zweiUhrzeitenAmSelbenTag() {
        assertThat(extract("Zwischen 18:30 Uhr und 18:40 Uhr drangen Einbrecher am Samstagabend (07.11.2026) ein."))
                .isEqualTo(new Occurrence(LocalDateTime.of(2026, 11, 7, 18, 30), LocalDateTime.of(2026, 11, 7, 18, 40)));
        assertThat(extract("am Samstag (07.11.2026) zwischen 19:00 Uhr und 20:30 Uhr"))
                .isEqualTo(new Occurrence(LocalDateTime.of(2026, 11, 7, 19, 0), LocalDateTime.of(2026, 11, 7, 20, 30)));
    }

    @Test
    void zeitraumUeberMitternachtEndetAmFolgetag() {
        assertThat(extract("In der Nacht zum Sonntag (08.11.2026) zwischen 23:30 Uhr und 01:15 Uhr"))
                .isEqualTo(new Occurrence(LocalDateTime.of(2026, 11, 8, 23, 30), LocalDateTime.of(2026, 11, 9, 1, 15)));
    }

    @Test
    void einzelnerZeitpunkt() {
        assertThat(extract("Gegen 00:15 Uhr am Montag (02.11.2026) befuhr ein Mann die L 3352."))
                .isEqualTo(new Occurrence(LocalDateTime.of(2026, 11, 2, 0, 15), null));
    }

    @Test
    void uhrzeitImFolgesatz() {
        assertThat(extract("Im Laufe des Freitags (06.11.2026) überstiegen Unbekannte das Hoftor. "
                + "Der Tatzeitraum kann auf 12:30 Uhr bis 22:45 Uhr eingegrenzt werden."))
                .isEqualTo(new Occurrence(LocalDateTime.of(2026, 11, 6, 12, 30), LocalDateTime.of(2026, 11, 6, 22, 45)));
    }

    @Test
    void datumOhneUhrzeitErgibtNichts() {
        assertThat(extract("Am Freitag (13.11.2026) beschädigten Unbekannte eine Scheibe.")).isNull();
        assertThat(extract("Keine Angabe")).isNull();
    }

    @Test
    void datumOhneJahrBekommtDasJahrDerVeroeffentlichung() {
        assertThat(extract("Am Dienstag (24.11.) gegen 22:10 Uhr brannte ein Container."))
                .isEqualTo(new Occurrence(LocalDateTime.of(2026, 11, 24, 22, 10), null));
    }

    @Test
    void tatzeitNachDerVeroeffentlichungWirdVerworfen() {
        assertThat(extract("Am 28.11.2026 um 10:00 Uhr findet eine Kontrolle statt.")).isNull();
    }
}
