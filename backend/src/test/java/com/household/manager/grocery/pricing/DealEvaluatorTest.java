package com.household.manager.grocery.pricing;

import com.household.manager.grocery.Chain;
import com.household.manager.grocery.entity.GroceryOffer;
import com.household.manager.grocery.entity.GroceryProduct;
import com.household.manager.grocery.matching.MatchingService.MatchedOffer;
import com.household.manager.grocery.matching.Verdict;
import com.household.manager.grocery.pricing.Deal.ReferenceKind;
import com.household.manager.grocery.pricing.UsualPriceCalculator.UsualPrice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DealEvaluatorTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneId.of("Europe/Berlin"));
    private final DealEvaluator evaluator = new DealEvaluator(clock);

    private static final UsualPrice USUAL = new UsualPrice(new BigDecimal("1.850"), 8, 4, false);
    private static final UsualPrice WARMING = new UsualPrice(null, 0, 0, true);

    private GroceryProduct product(String normalPrice) {
        GroceryProduct p = new GroceryProduct();
        p.setId(1L);
        p.setBaseUnit(BaseUnit.LITER);
        p.setNormalPricePerUnit(normalPrice == null ? null : new BigDecimal(normalPrice));
        return p;
    }

    /** Angebot mit Preis p, Grundpreis b (Faktor b/p für Umrechnungen). */
    private GroceryOffer offer(String price, String basePrice) {
        GroceryOffer o = new GroceryOffer();
        o.setChain(Chain.LIDL);
        o.setPrice(new BigDecimal(price));
        o.setBasePrice(new BigDecimal(basePrice));
        o.setBaseUnit(BaseUnit.LITER);
        o.setValidTo(LocalDate.of(2026, 11, 21));
        return o;
    }

    private MatchedOffer match(GroceryOffer o, Verdict v) {
        return new MatchedOffer(o, v, null, null, false);
    }

    @Test
    void dealAtTenPercentBelowMedian() {
        List<Deal> deals = evaluator.evaluate(product(null),
                List.of(match(offer("0.86", "1.43"), Verdict.MATCH)), USUAL, 10);

        assertThat(deals).hasSize(1);
        Deal d = deals.get(0);
        assertThat(d.savingPercent()).isEqualTo(23);
        assertThat(d.referenceKind()).isEqualTo(ReferenceKind.USUAL);
        assertThat(d.reference()).isEqualByComparingTo("1.850");
        assertThat(d.appOnly()).isFalse();
        assertThat(d.warmingUp()).isFalse();
    }

    @Test
    void noDealAtFivePercentBelow() {
        // 1.85 * 0.95 = 1.7575
        assertThat(evaluator.evaluate(product(null),
                List.of(match(offer("1.00", "1.76"), Verdict.MATCH)), USUAL, 10)).isEmpty();
    }

    @Test
    void thresholdIsInclusive() {
        // 1.85 * 0.9 = 1.665
        assertThat(evaluator.evaluate(product(null),
                List.of(match(offer("1.00", "1.665"), Verdict.MATCH)), USUAL, 10)).hasSize(1);
    }

    @Test
    void warmingUpUsesOwnNormalPriceThenLowest30Days() {
        List<Deal> own = evaluator.evaluate(product("1.850"),
                List.of(match(offer("0.86", "1.43"), Verdict.MATCH)), WARMING, 10);
        assertThat(own).hasSize(1);
        assertThat(own.get(0).referenceKind()).isEqualTo(ReferenceKind.OWN_NORMAL_PRICE);
        assertThat(own.get(0).warmingUp()).isTrue();

        GroceryOffer o = offer("0.80", "1.43");
        o.setLowestPrice30d(new BigDecimal("1.00"));
        // Faktor 1.43/0.80 = 1.7875 -> Referenz 1.7875; 1.43 <= 1.60875
        List<Deal> lowest = evaluator.evaluate(product(null), List.of(match(o, Verdict.MATCH)), WARMING, 10);
        assertThat(lowest).hasSize(1);
        assertThat(lowest.get(0).referenceKind()).isEqualTo(ReferenceKind.LOWEST_30_DAYS);
        assertThat(lowest.get(0).reference()).isEqualByComparingTo("1.788");
    }

    @Test
    void oldPriceIsNeverUsedAsReference() {
        GroceryOffer o = offer("0.86", "1.43");
        o.setOldPrice(new BigDecimal("2.99"));
        assertThat(evaluator.evaluate(product(null), List.of(match(o, Verdict.MATCH)), WARMING, 10)).isEmpty();
    }

    @Test
    void appOnlyDeal() {
        GroceryOffer o = offer("1.00", "1.80");
        o.setLoyaltyPrice(new BigDecimal("0.80")); // -> Grundpreis 1.44
        List<Deal> deals = evaluator.evaluate(product(null), List.of(match(o, Verdict.MATCH)), USUAL, 10);

        assertThat(deals).hasSize(1);
        assertThat(deals.get(0).appOnly()).isTrue();
        assertThat(deals.get(0).basePrice()).isEqualByComparingTo("1.440");
        assertThat(deals.get(0).savingPercent()).isEqualTo(22);
        assertThat(deals.get(0).appBasePrice()).isEqualByComparingTo("1.440");
    }

    @Test
    void appBasePriceIsSetEvenWhenNormalPriceQualifies() {
        GroceryOffer o = offer("0.86", "1.43");
        o.setLoyaltyPrice(new BigDecimal("0.50")); // Faktor 1.43/0.86 -> ca. 0.831
        Deal d = evaluator.evaluate(product(null), List.of(match(o, Verdict.MATCH)), USUAL, 10).get(0);

        assertThat(d.appOnly()).isFalse();
        assertThat(d.basePrice()).isEqualByComparingTo("1.43");
        assertThat(d.appBasePrice()).isEqualByComparingTo("0.831");
    }

    @Test
    void appBasePriceIsNullWithoutLoyaltyPrice() {
        Deal d = evaluator.evaluate(product(null), List.of(match(offer("0.86", "1.43"), Verdict.MATCH)), USUAL, 10).get(0);
        assertThat(d.appBasePrice()).isNull();
    }

    @Test
    void offerWithoutValidToCountsAsCurrent() {
        GroceryOffer o = offer("0.86", "1.43");
        o.setValidTo(null);
        assertThat(evaluator.evaluate(product(null), List.of(match(o, Verdict.MATCH)), USUAL, 10)).hasSize(1);
    }

    @Test
    void expiredOffersAreIgnored() {
        GroceryOffer o = offer("0.86", "1.43");
        o.setValidTo(LocalDate.of(2026, 11, 14));
        assertThat(evaluator.evaluate(product(null), List.of(match(o, Verdict.MATCH)), USUAL, 10)).isEmpty();

        o.setValidTo(LocalDate.of(2026, 11, 15));
        assertThat(evaluator.evaluate(product(null), List.of(match(o, Verdict.MATCH)), USUAL, 10)).hasSize(1);
    }

    @Test
    void unitMismatchIsNoDeal() {
        GroceryOffer o = offer("0.86", "1.43");
        o.setBaseUnit(BaseUnit.KILOGRAM);
        assertThat(evaluator.evaluate(product(null), List.of(match(o, Verdict.MATCH)), USUAL, 10)).isEmpty();
    }

    @Test
    void sortsMatchBeforeMaybeThenBySaving() {
        MatchedOffer maybeBig = match(offer("0.50", "0.90"), Verdict.MAYBE);
        MatchedOffer matchSmall = match(offer("0.86", "1.60"), Verdict.MATCH);
        MatchedOffer matchBig = match(offer("0.86", "1.43"), Verdict.MATCH);

        List<Deal> deals = evaluator.evaluate(product(null), List.of(maybeBig, matchSmall, matchBig), USUAL, 10);

        assertThat(deals).extracting(Deal::match).containsExactly(matchBig, matchSmall, maybeBig);
    }
}
