package com.household.manager.grocery;

import com.household.manager.grocery.pricing.BasePrice;
import com.household.manager.grocery.pricing.BaseUnit;
import com.household.manager.grocery.source.RawOffer;
import com.household.manager.grocery.source.SourceKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class OfferMergerTest {

    private static final LocalDate FROM = LocalDate.of(2026, 11, 16);
    private static final LocalDate TO = LocalDate.of(2026, 11, 21);
    private static final Map<String, Long> MARKETS = Map.of("REWE|240767", 7L);

    private final OfferMerger merger = new OfferMerger();

    private static RawOffer.RawOfferBuilder raw(SourceKey source, Chain chain, String market, String title, String price) {
        return RawOffer.builder()
                .source(source).sourceOfferId(source.name() + "-1").chain(chain).marketExternalId(market)
                .title(title).price(new BigDecimal(price)).validFrom(FROM).validTo(TO);
    }

    @Test
    void mergesSameOfferFromThreeSourcesIntoOne() {
        RawOffer marktguru = raw(SourceKey.MARKTGURU, Chain.REWE, null, "Cola", "7.99").brand("Coca-Cola")
                .imageUrl("http://img/mg.jpg").build();
        RawOffer kaufda = raw(SourceKey.KAUFDA, Chain.REWE, "240767", "Coca-Cola Cola", "7.99").build();
        RawOffer rewe = raw(SourceKey.REWE, Chain.REWE, "240767", "Coca-Cola, je 6 x 1-l-Fl.", "7.99")
                .loyaltyPrice(new BigDecimal("6.99")).build();

        List<OfferMerger.MergedOffer> merged = merger.merge(List.of(marktguru, kaufda, rewe), MARKETS);

        assertThat(merged).hasSize(1);
        OfferMerger.MergedOffer offer = merged.get(0);
        assertThat(offer.marketId()).isEqualTo(7L);
        assertThat(offer.sources()).containsExactlyInAnyOrder(SourceKey.MARKTGURU, SourceKey.KAUFDA, SourceKey.REWE);
        assertThat(offer.title()).isEqualTo("Coca-Cola, je 6 x 1-l-Fl.");
        assertThat(offer.loyaltyPrice()).isEqualByComparingTo("6.99");
        assertThat(offer.brand()).isEqualTo("Coca-Cola");
        assertThat(offer.imageUrl()).isEqualTo("http://img/mg.jpg");
        assertThat(offer.sourceOfferIds()).containsEntry(SourceKey.REWE, "REWE-1");
        assertThat(offer.fingerprint()).hasSize(64);
    }

    @Test
    void aggregatorOfferWithoutDirectStaysChainWide() {
        RawOffer marktguru = raw(SourceKey.MARKTGURU, Chain.ALDI_SUED, null, "Butter", "1.39").brand("Kerrygold").build();
        RawOffer kaufda = raw(SourceKey.KAUFDA, Chain.ALDI_SUED, null, "Kerrygold Butter", "1.39").build();

        List<OfferMerger.MergedOffer> merged = merger.merge(List.of(marktguru, kaufda), MARKETS);

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).marketId()).isNull();
        assertThat(merged.get(0).sources()).containsExactlyInAnyOrder(SourceKey.MARKTGURU, SourceKey.KAUFDA);
    }

    @Test
    void aggregatorMergesIntoEveryMatchingDirectMarket() {
        Map<String, Long> markets = Map.of("REWE|1", 1L, "REWE|2", 2L);
        RawOffer mg = raw(SourceKey.MARKTGURU, Chain.REWE, null, "Cola", "7.99").brand("Coca-Cola").build();
        RawOffer r1 = raw(SourceKey.REWE, Chain.REWE, "1", "Coca-Cola Cola", "7.99").build();
        RawOffer r2 = raw(SourceKey.REWE, Chain.REWE, "2", "Coca-Cola Cola", "7.99").build();

        List<OfferMerger.MergedOffer> merged = merger.merge(List.of(mg, r1, r2), markets);

        assertThat(merged).hasSize(2);
        assertThat(merged).allSatisfy(o -> assertThat(o.sources()).contains(SourceKey.MARKTGURU, SourceKey.REWE));
        assertThat(merged).extracting(OfferMerger.MergedOffer::marketId).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void differentPriceIsNotMerged() {
        RawOffer a = raw(SourceKey.MARKTGURU, Chain.LIDL, null, "Cola Zero", "7.99").build();
        RawOffer b = raw(SourceKey.KAUFDA, Chain.LIDL, null, "Cola Zero", "8.49").build();

        assertThat(merger.merge(List.of(a, b), MARKETS)).hasSize(2);
    }

    @Test
    void logsPriceConflict(CapturedOutput output) {
        RawOffer mg = raw(SourceKey.MARKTGURU, Chain.REWE, null, "Cola", "8.49").brand("Coca-Cola").build();
        RawOffer rewe = raw(SourceKey.REWE, Chain.REWE, "240767", "Coca-Cola Cola", "7.99").build();

        List<OfferMerger.MergedOffer> merged = merger.merge(List.of(mg, rewe), MARKETS);

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).price()).isEqualByComparingTo("7.99");
        assertThat(merged.get(0).sources()).contains(SourceKey.MARKTGURU, SourceKey.REWE);
        assertThat(output.getAll()).contains("Preisabweichung");
    }

    @Test
    void priceDifferenceOverThirtyPercentIsNotMerged() {
        RawOffer mg = raw(SourceKey.MARKTGURU, Chain.REWE, null, "Cola", "12.99").brand("Coca-Cola").build();
        RawOffer rewe = raw(SourceKey.REWE, Chain.REWE, "240767", "Coca-Cola Cola", "7.99").build();

        List<OfferMerger.MergedOffer> merged = merger.merge(List.of(mg, rewe), MARKETS);

        assertThat(merged).hasSize(2);
    }

    @Test
    void nonOverlappingValidityIsNotMerged() {
        RawOffer a = raw(SourceKey.MARKTGURU, Chain.LIDL, null, "Butter", "1.39").build();
        RawOffer b = raw(SourceKey.KAUFDA, Chain.LIDL, null, "Butter", "1.39")
                .validFrom(TO.plusDays(1)).validTo(TO.plusDays(7)).build();

        assertThat(merger.merge(List.of(a, b), MARKETS)).hasSize(2);
    }

    @Test
    void dissimilarTitlesAreNotMerged() {
        RawOffer a = raw(SourceKey.MARKTGURU, Chain.LIDL, null, "Butter Irische", "1.39").build();
        RawOffer b = raw(SourceKey.KAUFDA, Chain.LIDL, null, "Gurken eingelegt", "1.39").build();

        assertThat(merger.merge(List.of(a, b), MARKETS)).hasSize(2);
    }

    @Test
    void equalFingerprintMergesDespiteLowWordOverlap() {
        RawOffer a = raw(SourceKey.MARKTGURU, Chain.LIDL, null, "Butter", "1.39").build();
        RawOffer b = raw(SourceKey.KAUFDA, Chain.LIDL, null, "butter", "1.39").build();

        assertThat(merger.merge(List.of(a, b), MARKETS)).hasSize(1);
    }

    @Test
    void differentChainsAreNeverMerged() {
        RawOffer a = raw(SourceKey.MARKTGURU, Chain.LIDL, null, "Butter", "1.39").build();
        RawOffer b = raw(SourceKey.KAUFDA, Chain.ALDI_SUED, null, "Butter", "1.39").build();

        assertThat(merger.merge(List.of(a, b), MARKETS)).hasSize(2);
    }

    @Test
    void basePriceFromDirectSourceWinsAndMissingOneIsFilled() {
        RawOffer mg = raw(SourceKey.MARKTGURU, Chain.REWE, null, "Cola", "7.99").brand("Coca-Cola")
                .basePrice(new BasePrice(new BigDecimal("1.332"), BaseUnit.LITER)).build();
        RawOffer rewe = raw(SourceKey.REWE, Chain.REWE, "240767", "Coca-Cola Cola", "7.99").build();

        OfferMerger.MergedOffer offer = merger.merge(List.of(mg, rewe), MARKETS).get(0);

        assertThat(offer.basePrice()).isEqualTo(new BasePrice(new BigDecimal("1.332"), BaseUnit.LITER));
    }

    @Test
    void ignoresOffersWithoutTitleOrPrice() {
        RawOffer broken = RawOffer.builder().source(SourceKey.REWE).chain(Chain.REWE).build();

        assertThat(merger.merge(List.of(broken), Map.of())).isEmpty();
    }
}
