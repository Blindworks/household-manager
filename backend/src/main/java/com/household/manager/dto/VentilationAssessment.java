package com.household.manager.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Ergebnis der Lüftungsbewertung.
 *
 * <p>{@code recommended == null} heißt "keine Aussage möglich" (kein frischer
 * Außenwert) — bewusst verschieden von {@code false} ("kein Lüften nötig"),
 * damit das Frontend bei fehlender Datenlage keine Karte zeigt statt eine
 * falsche Entwarnung.
 */
public record VentilationAssessment(
        Boolean recommended,
        BigDecimal outdoorTemperature,
        List<VentilationRoom> rooms,
        LocalDateTime evaluatedAt
) {
}
