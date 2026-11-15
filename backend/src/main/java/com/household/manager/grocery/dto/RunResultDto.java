package com.household.manager.grocery.dto;

import java.util.Map;

/** Ergebnis eines Laufs; {@code aiFailures}: Claude-Aufrufe ohne Antwort; {@code sourceFailures}: Quelle → Fehlergrund. */
public record RunResultDto(int offers, int aiCalls, int deferred, int deals, int aiFailures,
                           Map<String, String> sourceFailures) {
}
