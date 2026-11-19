package com.household.manager.grocery;

import com.household.manager.grocery.entity.GroceryProduct;
import com.household.manager.grocery.pricing.BaseUnit;
import com.household.manager.grocery.pricing.NormalPriceEstimator;
import com.household.manager.repository.GroceryProductRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NormalPriceEstimatesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 20, 0);

    private final NormalPriceEstimator estimator = mock(NormalPriceEstimator.class);
    private final GroceryProductRepository products = mock(GroceryProductRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-17T19:00:00Z"), ZoneId.of("Europe/Berlin"));
    private final NormalPriceEstimates estimates = new NormalPriceEstimates(estimator, products, clock);

    private static GroceryProduct product(long id) {
        GroceryProduct p = new GroceryProduct();
        p.setId(id);
        p.setName("Kaffee");
        p.setBaseUnit(BaseUnit.KILOGRAM);
        return p;
    }

    @Test
    void dueOnlyWithoutOwnPriceAndWithoutFreshEstimate() {
        GroceryProduct fresh = product(1L);
        assertThat(NormalPriceEstimates.isDue(fresh, NOW)).isTrue();

        fresh.setEstimatedPricePerUnit(new BigDecimal("18.50"));
        fresh.setEstimatedAt(NOW.minusDays(29));
        assertThat(NormalPriceEstimates.isDue(fresh, NOW)).isFalse();

        fresh.setEstimatedAt(NOW.minusDays(31));
        assertThat(NormalPriceEstimates.isDue(fresh, NOW)).isTrue();

        GroceryProduct own = product(2L);
        own.setNormalPricePerUnit(new BigDecimal("19.00"));
        assertThat(NormalPriceEstimates.isDue(own, NOW)).isFalse();
    }

    @Test
    void storesEstimateAndSetsItOnTheEntityForThisRun() {
        GroceryProduct coffee = product(1L);
        when(estimator.estimate(any(), anyList())).thenReturn(Optional.of(new BigDecimal("18.50")));

        int calls = estimates.refreshDue(List.of(coffee), Map.of());

        assertThat(calls).isEqualTo(1);
        verify(products).updateEstimate(1L, new BigDecimal("18.50"), NOW);
        assertThat(coffee.getEstimatedPricePerUnit()).isEqualByComparingTo("18.50");
        assertThat(coffee.getEstimatedAt()).isEqualTo(NOW);
    }

    @Test
    void failedEstimateKeepsTheOldValueAndWritesNothing() {
        GroceryProduct coffee = product(1L);
        coffee.setEstimatedPricePerUnit(new BigDecimal("17.00"));
        coffee.setEstimatedAt(NOW.minusDays(40));
        when(estimator.estimate(any(), anyList())).thenReturn(Optional.empty());

        estimates.refreshDue(List.of(coffee), Map.of());

        verify(products, never()).updateEstimate(anyLong(), any(), any());
        assertThat(coffee.getEstimatedPricePerUnit()).isEqualByComparingTo("17.00");
    }

    @Test
    void capsTheCallsPerRun() {
        List<GroceryProduct> many = new ArrayList<>();
        for (long id = 1; id <= NormalPriceEstimates.MAX_PER_RUN + 5; id++) {
            many.add(product(id));
        }
        when(estimator.estimate(any(), anyList())).thenReturn(Optional.of(new BigDecimal("2.00")));

        int calls = estimates.refreshDue(many, Map.of());

        assertThat(calls).isEqualTo(NormalPriceEstimates.MAX_PER_RUN);
        verify(estimator, times(NormalPriceEstimates.MAX_PER_RUN)).estimate(any(), anyList());
    }
}
