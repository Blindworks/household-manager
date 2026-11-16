package com.household.manager.grocery.dto;

import java.time.LocalDateTime;

/**
 * Stand des Supermarkt-Laufs (nächtlich oder manuell), nur im Speicher. {@code lastResult} bzw. {@code lastError}
 * gehören zum zuletzt beendeten Lauf; nach einem Neustart ist alles leer.
 */
public record RunStatusDto(boolean running, LocalDateTime startedAt, LocalDateTime finishedAt,
                           RunResultDto lastResult, String lastError) {

    public static RunStatusDto idle() {
        return new RunStatusDto(false, null, null, null, null);
    }
}
