package com.household.manager.grocery.dto;

import java.math.BigDecimal;

/**
 * Zuordnung und Bewertung eines Produkts in einem Lauf: Kandidaten nach dem Vorfilter, davon ohne Urteil, neue
 * Claude-Urteile, sichere ({@code matches}) und unsichere ({@code maybes}) Treffer, Schnäppchen und Normalpreis
 * zum Laufende.
 */
public record RunProductLine(long productId, String name, int candidates, int unjudged, int newJudgements,
                             int aiCalls, int aiFailures, boolean deferred, String error, int matches, int maybes,
                             int deals, BigDecimal normalPrice, BigDecimal estimatedPrice, String baseUnit) {
}
