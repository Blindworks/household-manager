package com.household.manager.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Ein Balken der Verbrauchsansicht.
 *
 * @param periodStart Beginn der Periode (Ablesedatum bei Wochen, Monatserster bei Monaten)
 * @param label       Beschriftung der X-Achse, z. B. "KW 33" oder "Aug 26"
 * @param consumption Verbrauch in der Einheit der Serie
 * @param estimated   true, sobald mindestens eine beitragende Ablesung ein Schaetzwert war;
 *                    ein Balken kann mehrere Ablesungen derselben Periode zusammenfassen
 */
public record ConsumptionPoint(
        LocalDate periodStart,
        String label,
        BigDecimal consumption,
        boolean estimated
) {
}
