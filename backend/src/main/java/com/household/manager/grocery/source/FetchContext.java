package com.household.manager.grocery.source;

import com.household.manager.grocery.Chain;

import java.util.List;
import java.util.Set;

/** Eingaben eines Quellenabrufs: Standort, gewählte Märkte und eingeschaltete Ketten. */
public record FetchContext(
        String zip,
        double lat,
        double lng,
        List<MarketCandidate> selectedMarkets,
        Set<Chain> enabledChains
) {
}
