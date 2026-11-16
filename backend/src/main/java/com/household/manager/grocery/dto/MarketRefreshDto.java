package com.household.manager.grocery.dto;

import com.household.manager.grocery.Chain;

import java.util.List;

/** Ergebnis der Marktsuche: alle Kandidaten plus die Ketten, deren Suche scheiterte. */
public record MarketRefreshDto(List<MarketDto> markets, List<Chain> failedChains) {
}
