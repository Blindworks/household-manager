package com.household.manager.grocery.pricing;

import com.household.manager.grocery.Chain;
import com.household.manager.grocery.entity.GroceryPriceObservation;
import com.household.manager.grocery.pricing.UsualPriceCalculator.UsualPrice;
import com.household.manager.repository.GroceryPriceObservationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsualPriceCalculatorTest {

    private final GroceryPriceObservationRepository repo = mock(GroceryPriceObservationRepository.class);
    private final UsualPriceCalculator calculator = new UsualPriceCalculator(repo);
    private final LocalDate today = LocalDate.of(2026, 11, 15);

    private GroceryPriceObservation obs(Chain chain, String week, String price) {
        GroceryPriceObservation o = new GroceryPriceObservation();
        o.setChain(chain);
        o.setIsoWeek(week);
        o.setBasePrice(new BigDecimal(price));
        return o;
    }

    @Test
    void medianOfTwelveWeeksOnePerChainAndWeek() {
        when(repo.findByProductIdAndIsoWeekIn(anyLong(), anyCollection())).thenReturn(List.of(
                obs(Chain.EDEKA, "2026-W44", "1.10"), obs(Chain.REWE, "2026-W44", "1.20"),
                obs(Chain.EDEKA, "2026-W45", "1.15"), obs(Chain.REWE, "2026-W45", "1.30"),
                obs(Chain.EDEKA, "2026-W46", "1.05"), obs(Chain.REWE, "2026-W46", "1.25")));

        UsualPrice usual = calculator.usual(1, today);

        assertThat(usual.median()).isEqualByComparingTo("1.175");
        assertThat(usual.observations()).isEqualTo(6);
        assertThat(usual.distinctWeeks()).isEqualTo(3);
        assertThat(usual.warmingUp()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void windowCoversTwelveWeeksIncludingCurrent() {
        when(repo.findByProductIdAndIsoWeekIn(anyLong(), anyCollection())).thenReturn(List.of());
        calculator.usual(1, today);

        ArgumentCaptor<Collection<String>> weeks = ArgumentCaptor.forClass(Collection.class);
        verify(repo).findByProductIdAndIsoWeekIn(anyLong(), weeks.capture());
        assertThat(weeks.getValue()).hasSize(12).contains("2026-W46", "2026-W35").doesNotContain("2026-W34", "2026-W47");
    }

    @Test
    void warmingUpBelowSixObservationsOrThreeWeeks() {
        when(repo.findByProductIdAndIsoWeekIn(anyLong(), anyCollection())).thenReturn(List.of(
                obs(Chain.EDEKA, "2026-W45", "1.10"), obs(Chain.REWE, "2026-W45", "1.20"),
                obs(Chain.PENNY, "2026-W45", "1.15"), obs(Chain.LIDL, "2026-W45", "1.30"),
                obs(Chain.NORMA, "2026-W45", "1.05"), obs(Chain.KAUFLAND, "2026-W45", "1.25")));
        UsualPrice oneWeek = calculator.usual(1, today);
        assertThat(oneWeek.observations()).isEqualTo(6);
        assertThat(oneWeek.warmingUp()).isTrue();

        when(repo.findByProductIdAndIsoWeekIn(anyLong(), anyCollection())).thenReturn(List.of(
                obs(Chain.EDEKA, "2026-W44", "1.10"), obs(Chain.EDEKA, "2026-W45", "1.20"),
                obs(Chain.EDEKA, "2026-W46", "1.15")));
        UsualPrice fewObservations = calculator.usual(1, today);
        assertThat(fewObservations.distinctWeeks()).isEqualTo(3);
        assertThat(fewObservations.warmingUp()).isTrue();
    }

    @Test
    void noObservationsMeansNullMedian() {
        when(repo.findByProductIdAndIsoWeekIn(anyLong(), anyCollection())).thenReturn(List.of());
        UsualPrice usual = calculator.usual(1, today);
        assertThat(usual.median()).isNull();
        assertThat(usual.warmingUp()).isTrue();
    }
}
