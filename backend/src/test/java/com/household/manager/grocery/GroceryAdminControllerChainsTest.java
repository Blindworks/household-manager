package com.household.manager.grocery;

import com.household.manager.grocery.dto.ChainStatusDto;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroceryAdminControllerChainsTest {

    @Test
    void listsOnlyChainsWithoutMarketsWithTheirSwitch() {
        GroceryMarketService marketService = mock(GroceryMarketService.class);
        when(marketService.enabledChains()).thenReturn(EnumSet.of(Chain.REWE, Chain.LIDL));
        GroceryAdminController controller = new GroceryAdminController(
                null, marketService, null, null, null, null);

        List<ChainStatusDto> chains = controller.getChains();

        assertThat(chains).extracting(ChainStatusDto::chain)
                .containsExactly(Chain.LIDL, Chain.ALDI_SUED, Chain.NETTO_MD, Chain.NORMA);
        assertThat(chains.get(0).enabled()).isTrue();
        assertThat(chains.get(1).enabled()).isFalse();
        assertThat(chains.get(0).name()).isEqualTo("Lidl");
    }
}
