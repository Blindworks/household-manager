package com.household.manager.service;

import lombok.Getter;

import java.time.LocalDate;

/**
 * Waehlbarer Zeitraum der Verbrauchsansicht: Auflösung plus Anzahl der Perioden.
 *
 * <p>Bewusst nicht {@link SeriesRange}: das beschreibt bei Temperatur und Luftqualitaet
 * Fenster von Tagen und kann Wochen- oder Monatszahlen nicht ausdruecken. Es bedient
 * bereits zwei Serien-Services; eine dritte, andersartige Bedeutung hineinzuzwingen
 * waere der teurere Weg.
 */
@Getter
public enum ConsumptionRange {
    WEEKS_8(ConsumptionResolution.WEEK, 8),
    WEEKS_26(ConsumptionResolution.WEEK, 26),
    WEEKS_52(ConsumptionResolution.WEEK, 52),
    MONTHS_6(ConsumptionResolution.MONTH, 6),
    MONTHS_12(ConsumptionResolution.MONTH, 12),
    MONTHS_24(ConsumptionResolution.MONTH, 24);

    private final ConsumptionResolution resolution;
    private final int periods;

    ConsumptionRange(ConsumptionResolution resolution, int periods) {
        this.resolution = resolution;
        this.periods = periods;
    }

    /**
     * Beginn des Ladefensters. Bei Monaten der Erste des aeltesten gezeigten Monats -
     * ein mitten im Monat beginnendes Fenster liesse den aeltesten Balken zu niedrig
     * erscheinen, weil ihm die ersten Wochen fehlten.
     */
    public LocalDate windowStart(LocalDate today) {
        return resolution == ConsumptionResolution.WEEK
                ? today.minusWeeks(periods)
                : today.minusMonths(periods - 1L).withDayOfMonth(1);
    }
}
