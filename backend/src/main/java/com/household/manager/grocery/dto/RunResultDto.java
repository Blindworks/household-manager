package com.household.manager.grocery.dto;

import java.util.Map;

/** Ergebnis eines Laufs; {@code sourceFailures}: Quelle → Fehlergrund. */
public record RunResultDto(int offers, int aiCalls, int deferred, int deals, Map<String, String> sourceFailures) {
}
