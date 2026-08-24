package com.household.manager.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ConsumptionRangeTest {

    @Test
    void wochenzeitraeumeTragenDieAufloesungWoche() {
        assertThat(ConsumptionRange.WEEKS_26.getResolution()).isEqualTo(ConsumptionResolution.WEEK);
        assertThat(ConsumptionRange.WEEKS_26.getPeriods()).isEqualTo(26);
    }

    @Test
    void monatszeitraeumeTragenDieAufloesungMonat() {
        assertThat(ConsumptionRange.MONTHS_12.getResolution()).isEqualTo(ConsumptionResolution.MONTH);
        assertThat(ConsumptionRange.MONTHS_12.getPeriods()).isEqualTo(12);
    }

    /**
     * Der Startpunkt bestimmt, wie weit zurueck Ablesungen geladen werden. Bei Wochen
     * exakt N Wochen, bei Monaten der Erste des Monats vor N-1 Monaten - sonst fehlte
     * dem aeltesten Monatsbalken sein Anfang und er stuende zu niedrig da.
     */
    @Test
    void berechnetDenFensterbeginnJeAufloesung() {
        LocalDate heute = LocalDate.of(2026, 8, 24);

        assertThat(ConsumptionRange.WEEKS_8.windowStart(heute)).isEqualTo(LocalDate.of(2026, 6, 29));
        assertThat(ConsumptionRange.MONTHS_6.windowStart(heute)).isEqualTo(LocalDate.of(2026, 3, 1));
    }
}
