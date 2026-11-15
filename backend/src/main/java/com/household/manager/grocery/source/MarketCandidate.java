package com.household.manager.grocery.source;

import com.household.manager.grocery.Chain;

/** Ein von einer Kette gefundener Markt; {@code region} ist nur bei Penny (sellingRegion) gesetzt. */
public record MarketCandidate(
        Chain chain,
        String externalId,
        String name,
        String address,
        Double lat,
        Double lng,
        String region
) {
}
