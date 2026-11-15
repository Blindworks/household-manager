package com.household.manager.grocery;

import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.grocery.entity.GroceryOffer;
import com.household.manager.grocery.entity.GroceryProduct;
import com.household.manager.grocery.matching.MatchingService.MatchedOffer;
import com.household.manager.grocery.matching.Verdict;
import com.household.manager.grocery.pricing.BaseUnit;
import com.household.manager.grocery.pricing.Deal;
import com.household.manager.model.entity.AppUser;
import com.household.manager.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroceryEntityReporterTest {

    private final EntityStateService entityStateService = mock(EntityStateService.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final GroceryEntityReporter reporter =
            new GroceryEntityReporter(entityStateService, new GroceryOwners(users, null));

    static GroceryProduct product(long id, Long owner, String name) {
        GroceryProduct p = new GroceryProduct();
        p.setId(id);
        p.setOwnerUserId(owner);
        p.setName(name);
        p.setBaseUnit(BaseUnit.LITER);
        p.setActive(true);
        return p;
    }

    static Deal deal(Chain chain, String basePrice, int saving) {
        GroceryOffer o = new GroceryOffer();
        o.setChain(chain);
        o.setBasePrice(new BigDecimal(basePrice));
        o.setBaseUnit(BaseUnit.LITER);
        o.setFingerprint("fp-" + chain + basePrice);
        MatchedOffer m = new MatchedOffer(o, Verdict.MATCH, null, null, false);
        return new Deal(m, new BigDecimal(basePrice), new BigDecimal("2.000"), Deal.ReferenceKind.USUAL, saving,
                false, false, null);
    }

    private Map<String, EntityStateUpdate> reported(int expectedCalls) {
        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(expectedCalls)).reportState(captor.capture());
        return captor.getAllValues().stream()
                .collect(Collectors.toMap(EntityStateUpdate::entityId, Function.identity()));
    }

    @Test
    void entityStateCountsDeals() {
        AppUser user = AppUser.builder().id(3L).displayName("Benedikt").build();
        when(users.findAll()).thenReturn(List.of(user));

        reporter.report(Map.of(1L, List.of(deal(Chain.PENNY, "1.43", 23)), 2L, List.of(deal(Chain.LIDL, "0.99", 30))),
                List.of(product(1L, 3L, "Cola Zero"), product(2L, 3L, "Milch")));

        Map<String, EntityStateUpdate> byId = reported(2);
        EntityStateUpdate userEntity = byId.get("sensor.grocery_deals_3");
        assertThat(userEntity).isNotNull();
        assertThat(userEntity.state()).isEqualTo("2");
        assertThat(userEntity.source()).isEqualTo(EntitySource.GROCERY);
        assertThat(userEntity.friendlyName()).isEqualTo("Schnäppchen Benedikt");
        assertThat(userEntity.attributes()).doesNotContainKeys("unit", "deviceClass");
        assertThat(userEntity.attributes().get("items"))
                .isEqualTo(List.of("Milch: Lidl 0,99 €/l", "Cola Zero: Penny 1,43 €/l"));
    }

    @Test
    void householdEntityAlwaysReported() {
        when(users.findAll()).thenReturn(List.of());

        reporter.report(Map.of(), List.of());

        EntityStateUpdate household = reported(1).get("sensor.grocery_deals_household");
        assertThat(household.state()).isEqualTo("0");
        assertThat(household.friendlyName()).isEqualTo("Schnäppchen Haushalt");
        assertThat(household.attributes().get("items")).isEqualTo(List.of());
    }

    @Test
    void itemsAreCappedAtFiveAndReporterNeverThrows() {
        when(users.findAll()).thenThrow(new RuntimeException("DB weg"));
        List<Deal> many = List.of(deal(Chain.PENNY, "1.01", 40), deal(Chain.PENNY, "1.02", 39),
                deal(Chain.PENNY, "1.03", 38), deal(Chain.PENNY, "1.04", 37), deal(Chain.PENNY, "1.05", 36),
                deal(Chain.PENNY, "1.06", 35));
        doThrow(new RuntimeException("kaputt")).doNothing().when(entityStateService).reportState(any());

        assertThatCode(() -> reporter.report(Map.of(1L, many, 2L, many),
                List.of(product(1L, null, "Cola"), product(2L, 7L, "Saft")))).doesNotThrowAnyException();

        Map<String, EntityStateUpdate> byId = reported(2);
        EntityStateUpdate any = byId.values().stream().filter(u -> "6".equals(u.state())).findFirst().orElseThrow();
        assertThat((List<?>) any.attributes().get("items")).hasSize(5);
    }
}
