package com.household.manager.blaulicht;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class NightWindowTest {

    private final BlaulichtProperties.Briefing briefing = new BlaulichtProperties().getBriefing();

    @Test
    void nachtEndetMorgensUndBeginntAmVorabend() {
        NightWindow night = NightWindow.endingOn(LocalDate.of(2026, 11, 25), briefing);

        assertThat(night.start()).isEqualTo(LocalDateTime.of(2026, 11, 24, 20, 0));
        assertThat(night.end()).isEqualTo(LocalDateTime.of(2026, 11, 25, 8, 0));
    }

    @Test
    void tagsueberDieVergangeneNachtNachtsDieLaufende() {
        assertThat(NightWindow.current(LocalDateTime.of(2026, 11, 25, 14, 0), briefing).end())
                .isEqualTo(LocalDateTime.of(2026, 11, 25, 8, 0));
        assertThat(NightWindow.current(LocalDateTime.of(2026, 11, 25, 22, 0), briefing).end())
                .isEqualTo(LocalDateTime.of(2026, 11, 26, 8, 0));
        assertThat(NightWindow.current(LocalDateTime.of(2026, 11, 25, 3, 0), briefing).end())
                .isEqualTo(LocalDateTime.of(2026, 11, 25, 8, 0));
    }

    @Test
    void tatzeitEntscheidetVorVeroeffentlichung() {
        NightWindow night = NightWindow.endingOn(LocalDate.of(2026, 11, 25), briefing);

        assertThat(night.contains(event(at(24, 23), null, at(25, 15)))).isTrue();
        assertThat(night.contains(event(at(24, 14), null, at(24, 22)))).isFalse();
        // Zeitraum, der in die Nacht hineinreicht
        assertThat(night.contains(event(at(24, 18), at(24, 21), at(25, 15)))).isTrue();
        // unscharf (über 16 h): Veröffentlichung zählt
        assertThat(night.contains(event(at(22, 18), at(24, 22), at(25, 15)))).isFalse();
        assertThat(night.contains(event(null, null, at(25, 1)))).isTrue();
        assertThat(night.contains(event(null, null, at(25, 8)))).isFalse();
    }

    static LocalDateTime at(int day, int hour) {
        return LocalDateTime.of(2026, 11, day, hour, 0);
    }

    static BlaulichtEvent event(LocalDateTime from, LocalDateTime until, LocalDateTime published) {
        BlaulichtEvent event = new BlaulichtEvent();
        event.setOccurredAt(from);
        event.setOccurredUntil(until);
        event.setPublishedAt(published);
        event.setCategory(BlaulichtCategory.EINBRUCH);
        event.setLocality("Bad Vilbel");
        event.setSource(BlaulichtSourceKey.PRESSEPORTAL);
        event.setTitle("Einbruch");
        return event;
    }
}
