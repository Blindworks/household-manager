package com.household.manager.grocery;

import com.household.manager.grocery.GroceryRunService.Snapshot;
import com.household.manager.grocery.dto.GrocerySummaryDto;
import com.household.manager.grocery.dto.GrocerySummaryDto.SummaryItem;
import com.household.manager.grocery.entity.GroceryMarket;
import com.household.manager.grocery.matching.Verdict;
import com.household.manager.grocery.pricing.Deal;
import com.household.manager.repository.GroceryMarketRepository;
import com.household.manager.repository.GroceryPriceObservationRepository;
import com.household.manager.repository.GroceryProductRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroceryDealQueryServiceTest {

    private final GroceryRunService runService = mock(GroceryRunService.class);
    private final GroceryProductRepository products = mock(GroceryProductRepository.class);
    private final GroceryMarketRepository markets = mock(GroceryMarketRepository.class);
    private final GroceryOwners owners = mock(GroceryOwners.class);
    private final GroceryDealQueryService service = new GroceryDealQueryService(runService, products, markets,
            mock(GroceryPriceObservationRepository.class), owners, Clock.systemUTC());

    @Test
    void summaryCarriesOfferDetailsForTheDialogAndSkipsUnsureDeals() {
        Deal sure = GroceryEntityReporterTest.deal(Chain.PENNY, "1.430", 23);
        sure.match().offer().setTitle("Coca-Cola Zero 1,25 l");
        sure.match().offer().setPrice(new BigDecimal("1.79"));
        sure.match().offer().setMarketId(7L);
        sure.match().offer().setValidTo(LocalDate.of(2026, 11, 21));
        sure.match().offer().setImageUrl("https://example.org/cola.jpg");
        Deal maybe = GroceryEntityReporterTest.deal(Chain.LIDL, "1.200", 30, Verdict.MAYBE, false);
        when(products.findAll()).thenReturn(List.of(GroceryEntityReporterTest.product(1L, null, "Cola Zero")));
        when(runService.snapshot()).thenReturn(new Snapshot(Map.of(), Map.of(1L, List.of(sure, maybe)),
                LocalDateTime.now()));
        when(owners.displayNames()).thenReturn(Map.of());
        GroceryMarket market = new GroceryMarket();
        market.setId(7L);
        market.setName("Penny Bad Vilbel");
        when(markets.findAll()).thenReturn(List.of(market));

        GrocerySummaryDto summary = service.summary();

        assertThat(summary.items()).hasSize(1);
        SummaryItem item = summary.items().get(0);
        assertThat(item.deal().title()).isEqualTo("Coca-Cola Zero 1,25 l");
        assertThat(item.deal().marketName()).isEqualTo("Penny Bad Vilbel");
        assertThat(item.deal().price()).isEqualByComparingTo("1.79");
        assertThat(item.deal().reference()).isEqualByComparingTo("2.000");
        assertThat(item.deal().validTo()).isEqualTo(LocalDate.of(2026, 11, 21));
        assertThat(item.deal().imageUrl()).isEqualTo("https://example.org/cola.jpg");
    }
}
