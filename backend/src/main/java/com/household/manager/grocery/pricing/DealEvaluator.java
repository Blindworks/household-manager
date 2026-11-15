package com.household.manager.grocery.pricing;

import com.household.manager.grocery.entity.GroceryOffer;
import com.household.manager.grocery.entity.GroceryProduct;
import com.household.manager.grocery.matching.MatchingService.MatchedOffer;
import com.household.manager.grocery.matching.Verdict;
import com.household.manager.grocery.pricing.Deal.ReferenceKind;
import com.household.manager.grocery.pricing.UsualPriceCalculator.UsualPrice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Entscheidet, welche zugeordneten Angebote echte Schnäppchen sind. Ein Streichpreis ist nie Maßstab.
 * Ein Angebot ohne Enddatum ({@code validTo == null}) gilt als aktuell.
 */
@Component
@RequiredArgsConstructor
public class DealEvaluator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final Clock clock;

    private record Reference(BigDecimal value, ReferenceKind kind) {
    }

    public List<Deal> evaluate(GroceryProduct product, List<MatchedOffer> matches, UsualPrice usual,
                               int thresholdPercent) {
        LocalDate today = LocalDate.now(clock);
        List<Deal> deals = new ArrayList<>();
        for (MatchedOffer match : matches) {
            if (isCurrent(match.offer(), today)) {
                evaluateOne(product, match, usual, thresholdPercent).ifPresent(deals::add);
            }
        }
        deals.sort(Comparator
                .comparing((Deal d) -> d.match().verdict() == Verdict.MATCH ? 0 : 1)
                .thenComparing(Comparator.comparingInt(Deal::savingPercent).reversed()));
        return deals;
    }

    private boolean isCurrent(GroceryOffer offer, LocalDate today) {
        return offer.getValidTo() == null || !offer.getValidTo().isBefore(today);
    }

    private Optional<Deal> evaluateOne(GroceryProduct product, MatchedOffer match, UsualPrice usual, int threshold) {
        GroceryOffer offer = match.offer();
        if (offer.getBasePrice() == null || offer.getBaseUnit() != product.getBaseUnit()) {
            return Optional.empty();
        }
        Optional<Reference> reference = reference(product, offer, usual);
        if (reference.isEmpty()) {
            return Optional.empty();
        }
        Reference ref = reference.get();
        BigDecimal app = scale(offer, offer.getLoyaltyPrice()).orElse(null);
        if (isBelowThreshold(offer.getBasePrice(), ref.value(), threshold)) {
            return Optional.of(deal(match, offer.getBasePrice(), ref, false, usual, app));
        }
        if (app != null && isBelowThreshold(app, ref.value(), threshold)) {
            return Optional.of(deal(match, app, ref, true, usual, app));
        }
        return Optional.empty();
    }

    private Optional<Reference> reference(GroceryProduct product, GroceryOffer offer, UsualPrice usual) {
        if (!usual.warmingUp() && usual.median() != null) {
            return Optional.of(new Reference(usual.median(), ReferenceKind.USUAL));
        }
        if (product.getNormalPricePerUnit() != null) {
            return Optional.of(new Reference(product.getNormalPricePerUnit(), ReferenceKind.OWN_NORMAL_PRICE));
        }
        return scale(offer, offer.getLowestPrice30d())
                .map(v -> new Reference(v, ReferenceKind.LOWEST_30_DAYS));
    }

    /** Rechnet einen Preis über das Verhältnis Grundpreis/Preis des Angebots in den Grundpreis um. */
    private Optional<BigDecimal> scale(GroceryOffer offer, BigDecimal otherPrice) {
        if (otherPrice == null || offer.getPrice() == null || offer.getBasePrice() == null
                || offer.getPrice().signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal factor = offer.getBasePrice().divide(offer.getPrice(), 10, RoundingMode.HALF_UP);
        return Optional.of(otherPrice.multiply(factor).setScale(3, RoundingMode.HALF_UP));
    }

    private boolean isBelowThreshold(BigDecimal basePrice, BigDecimal reference, int thresholdPercent) {
        BigDecimal limit = reference.multiply(HUNDRED.subtract(BigDecimal.valueOf(thresholdPercent)))
                .divide(HUNDRED, 6, RoundingMode.HALF_UP);
        return basePrice.compareTo(limit) <= 0;
    }

    private Deal deal(MatchedOffer match, BigDecimal basePrice, Reference ref, boolean appOnly, UsualPrice usual,
                      BigDecimal appBasePrice) {
        int saving = ref.value().subtract(basePrice).multiply(HUNDRED)
                .divide(ref.value(), 0, RoundingMode.HALF_UP).intValue();
        return new Deal(match, basePrice, ref.value(), ref.kind(), saving, appOnly, usual.warmingUp(), appBasePrice);
    }
}
