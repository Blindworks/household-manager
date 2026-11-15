package com.household.manager.grocery.pricing;

import com.household.manager.grocery.Chain;
import com.household.manager.grocery.entity.GroceryOffer;
import com.household.manager.grocery.entity.GroceryPriceObservation;
import com.household.manager.grocery.entity.GroceryProduct;
import com.household.manager.grocery.matching.MatchingService.MatchedOffer;
import com.household.manager.grocery.matching.ReasonKind;
import com.household.manager.grocery.matching.Verdict;
import com.household.manager.repository.GroceryPriceObservationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceObservationRecorderTest {

    private final GroceryPriceObservationRepository repo = mock(GroceryPriceObservationRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneId.of("Europe/Berlin"));
    private final PriceObservationRecorder recorder = new PriceObservationRecorder(repo, clock);

    private GroceryProduct product() {
        GroceryProduct p = new GroceryProduct();
        p.setId(1L);
        p.setBaseUnit(BaseUnit.LITER);
        return p;
    }

    private MatchedOffer match(String basePrice, BaseUnit unit, Verdict verdict, ReasonKind kind) {
        GroceryOffer o = new GroceryOffer();
        o.setChain(Chain.EDEKA);
        o.setBasePrice(basePrice == null ? null : new BigDecimal(basePrice));
        o.setBaseUnit(unit);
        o.setValidFrom(LocalDate.of(2026, 11, 16));
        return new MatchedOffer(o, verdict, kind, null, false);
    }

    @Test
    void recordsMatchWithIsoWeekOfValidFrom() {
        when(repo.findByProductIdAndChainAndIsoWeek(any(), any(), any())).thenReturn(Optional.empty());

        recorder.record(product(), List.of(match("1.43", BaseUnit.LITER, Verdict.MATCH, null)));

        ArgumentCaptor<GroceryPriceObservation> saved = ArgumentCaptor.forClass(GroceryPriceObservation.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getIsoWeek()).isEqualTo("2026-W47");
        assertThat(saved.getValue().getBasePrice()).isEqualByComparingTo("1.43");
        assertThat(saved.getValue().getChain()).isEqualTo(Chain.EDEKA);
    }

    @Test
    void upsertKeepsLowestPrice() {
        GroceryPriceObservation existing = new GroceryPriceObservation();
        existing.setBasePrice(new BigDecimal("1.20"));
        when(repo.findByProductIdAndChainAndIsoWeek(any(), any(), any())).thenReturn(Optional.of(existing));

        recorder.record(product(), List.of(match("1.43", BaseUnit.LITER, Verdict.MATCH, null)));
        verify(repo, never()).save(any());

        recorder.record(product(), List.of(match("1.10", BaseUnit.LITER, Verdict.MATCH, null)));
        verify(repo).save(existing);
        assertThat(existing.getBasePrice()).isEqualByComparingTo("1.10");
    }

    @Test
    void packagingDiffersConfirmedNotRecorded() {
        recorder.record(product(), List.of(match("1.43", BaseUnit.LITER, Verdict.MATCH, ReasonKind.PACKAGING_DIFFERS)));
        verify(repo, never()).save(any());
    }

    @Test
    void maybeWrongUnitAndMissingBasePriceNotRecorded() {
        recorder.record(product(), List.of(
                match("1.43", BaseUnit.LITER, Verdict.MAYBE, ReasonKind.SORT_UNCLEAR),
                match("1.43", BaseUnit.KILOGRAM, Verdict.MATCH, null),
                match(null, BaseUnit.LITER, Verdict.MATCH, null)));
        verify(repo, never()).save(any());
    }

    @Test
    void neverThrows() {
        when(repo.findByProductIdAndChainAndIsoWeek(any(), any(), any())).thenThrow(new IllegalStateException("db"));
        assertThatCode(() -> recorder.record(product(), List.of(match("1.43", BaseUnit.LITER, Verdict.MATCH, null))))
                .doesNotThrowAnyException();
    }
}
