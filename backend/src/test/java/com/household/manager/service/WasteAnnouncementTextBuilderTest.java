package com.household.manager.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WasteAnnouncementTextBuilderTest {

    private final WasteAnnouncementTextBuilder builder = new WasteAnnouncementTextBuilder();

    @Test
    void einLabel() {
        assertThat(builder.buildTomorrowText(List.of("Biotonne")))
                .isEqualTo("Erinnerung: Morgen wird abgeholt: Biotonne.");
    }

    @Test
    void zweiLabelsWerdenMitUndVerbunden() {
        assertThat(builder.buildTomorrowText(List.of("Biotonne", "Restmuell")))
                .isEqualTo("Erinnerung: Morgen wird abgeholt: Biotonne und Restmuell.");
    }

    @Test
    void dreiLabelsNutzenKommaUndAmEndeUnd() {
        assertThat(builder.buildTomorrowText(List.of("Biotonne", "Restmuell", "Gelber Sack")))
                .isEqualTo("Erinnerung: Morgen wird abgeholt: Biotonne, Restmuell und Gelber Sack.");
    }

    @Test
    void emptyLabelsWerftIllegalArgumentException() {
        assertThatThrownBy(() -> builder.buildTomorrowText(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("labels must not be empty");
    }
}
