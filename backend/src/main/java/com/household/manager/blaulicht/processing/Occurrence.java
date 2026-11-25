package com.household.manager.blaulicht.processing;

import java.time.Duration;
import java.time.LocalDateTime;

/** Tatzeit oder Tatzeitraum ({@code until} null bei einem Zeitpunkt). */
public record Occurrence(LocalDateTime from, LocalDateTime until) {

    /** Längere Zeiträume („von Freitag bis Montag“) taugen nicht, um eine Meldung einer Nacht zuzuordnen. */
    public static final Duration MAX_PRECISE = Duration.ofHours(16);

    public static boolean isPrecise(LocalDateTime from, LocalDateTime until) {
        return from != null && (until == null || !Duration.between(from, until).minus(MAX_PRECISE).isPositive());
    }
}
