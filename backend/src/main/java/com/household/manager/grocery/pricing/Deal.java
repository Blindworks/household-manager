package com.household.manager.grocery.pricing;

import com.household.manager.grocery.matching.MatchingService.MatchedOffer;

import java.math.BigDecimal;

/** Ein Angebot, das wirklich billiger als üblich ist. {@code basePrice} ist bei {@code appOnly} der App-Grundpreis. */
public record Deal(MatchedOffer match, BigDecimal basePrice, BigDecimal reference, ReferenceKind referenceKind,
                   int savingPercent, boolean appOnly, boolean warmingUp) {

    public enum ReferenceKind { USUAL, OWN_NORMAL_PRICE, LOWEST_30_DAYS }
}
