package com.household.manager.blaulicht;

import com.household.manager.blaulicht.processing.Occurrence;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Das Nachtfenster [Beginn, Ende) — einzige Definition, welche Meldung „heute Nacht“ war.
 *
 * <p>Eine Meldung gehört zur Nacht, wenn ihre Tatzeit (oder ihr Tatzeitraum) das Fenster berührt. Ohne
 * Tatzeit oder bei einem unscharfen Zeitraum über 16 Stunden („Freitag bis Montag“) zählt stattdessen die
 * Veröffentlichung.
 */
public record NightWindow(LocalDateTime start, LocalDateTime end) {

    /** Die Nacht, die am {@code morning} um {@code nightEnd} endet. */
    public static NightWindow endingOn(LocalDate morning, BlaulichtProperties.Briefing briefing) {
        LocalDateTime end = morning.atTime(briefing.getNightEnd());
        LocalTime startTime = briefing.getNightStart();
        LocalDateTime start = startTime.isBefore(briefing.getNightEnd())
                ? morning.atTime(startTime)
                : morning.minusDays(1).atTime(startTime);
        return new NightWindow(start, end);
    }

    /** Tagsüber die vergangene Nacht, nachts die laufende. */
    public static NightWindow current(LocalDateTime now, BlaulichtProperties.Briefing briefing) {
        NightWindow today = endingOn(now.toLocalDate(), briefing);
        if (now.isBefore(today.end())) {
            return today;
        }
        NightWindow tomorrow = endingOn(now.toLocalDate().plusDays(1), briefing);
        return now.isBefore(tomorrow.start()) ? today : tomorrow;
    }

    public boolean contains(BlaulichtEvent event) {
        if (Occurrence.isPrecise(event.getOccurredAt(), event.getOccurredUntil())) {
            LocalDateTime from = event.getOccurredAt();
            LocalDateTime until = event.getOccurredUntil() != null ? event.getOccurredUntil() : from;
            return from.isBefore(end) && !until.isBefore(start);
        }
        return !event.getPublishedAt().isBefore(start) && event.getPublishedAt().isBefore(end);
    }
}
