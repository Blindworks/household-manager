package com.household.manager.grocery.source;

import com.household.manager.grocery.Chain;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RawOfferTest {

    @Test
    void builderKeepsNullableFields() {
        RawOffer offer = RawOffer.builder()
                .source(SourceKey.REWE)
                .sourceOfferId("1")
                .chain(Chain.REWE)
                .title("Coca-Cola")
                .build();

        assertThat(offer.basePrice()).isNull();
        assertThat(offer.marketExternalId()).isNull();
        assertThat(offer.loyaltyPrice()).isNull();
        assertThat(Chain.REWE.hasMarkets()).isTrue();
        assertThat(Chain.LIDL.hasMarkets()).isFalse();
        assertThat(Chain.ALDI_SUED.displayName()).isEqualTo("Aldi Süd");
        assertThat(SourceKey.MARKTGURU.isAggregator()).isTrue();
        assertThat(SourceKey.LIDL.isAggregator()).isFalse();
    }
}
