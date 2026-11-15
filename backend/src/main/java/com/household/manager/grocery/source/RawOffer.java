package com.household.manager.grocery.source;

import com.household.manager.grocery.Chain;
import com.household.manager.grocery.pricing.BasePrice;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Ein Angebot, wie eine Quelle es liefert. {@code marketExternalId} ist null, wenn es kettenweit gilt;
 * {@code basePrice} ist null, wenn kein Grundpreis zuverlässig bestimmbar ist.
 */
@Builder
public record RawOffer(
        SourceKey source,
        String sourceOfferId,
        Chain chain,
        String marketExternalId,
        String title,
        String brand,
        String description,
        String packaging,
        BigDecimal price,
        BigDecimal oldPrice,
        BigDecimal loyaltyPrice,
        BigDecimal lowestPrice30Days,
        BasePrice basePrice,
        String basePriceText,
        LocalDate validFrom,
        LocalDate validTo,
        String imageUrl
) {
}
