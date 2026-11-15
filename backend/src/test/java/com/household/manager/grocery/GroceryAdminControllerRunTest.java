package com.household.manager.grocery;

import com.household.manager.audit.AuditService;
import com.household.manager.exception.TooManyRequestsException;
import com.household.manager.grocery.dto.ChainEnabledDto;
import com.household.manager.grocery.dto.GrocerySettingsDto;
import com.household.manager.grocery.dto.MarketDto;
import com.household.manager.grocery.dto.MarketSelectionDto;
import com.household.manager.grocery.dto.RunStartedDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroceryAdminControllerRunTest {

    private final GroceryRunService runService = mock(GroceryRunService.class);
    private final GroceryProperties properties = new GroceryProperties();
    private final GrocerySettingsService settings = mock(GrocerySettingsService.class);
    private final GroceryMarketService marketService = mock(GroceryMarketService.class);
    private final AuditService audit = mock(AuditService.class);
    private final GroceryAdminController controller =
            new GroceryAdminController(settings, marketService, null, properties, audit, runService);

    @Test
    void thresholdChangeRecomputesDealsWithoutClaude() {
        controller.updateSettings(new GrocerySettingsDto("60311", 50.1, 8.6, 15));

        verify(settings).update(any());
        verify(runService).rebuildFromDatabase();
        verify(runService, never()).run();
    }

    @Test
    void marketSelectionAndChainSwitchRecomputeDeals() {
        when(marketService.setSelected(7L, true)).thenReturn(new MarketDto(7L, Chain.REWE, "1", "REWE Markt", "Str. 1", null, null, true));

        controller.selectMarket(7L, new MarketSelectionDto(true));
        controller.updateChain(Chain.LIDL, new ChainEnabledDto(false));

        verify(runService, times(2)).rebuildFromDatabase();
    }

    @Test
    void acceptedRunAnswers202Started() {
        ResponseEntity<RunStartedDto> response = controller.runNow();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isEqualTo(new RunStartedDto("gestartet"));
        verify(runService).runManually();
    }

    @Test
    void cooldownOrRunningRunStays429() {
        doThrow(new TooManyRequestsException("Bitte 10 Minuten warten.")).when(runService).runManually();

        assertThatThrownBy(controller::runNow).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void disabledFeatureIsRejectedWithoutStarting() {
        properties.setEnabled(false);

        assertThatThrownBy(controller::runNow).isInstanceOf(IllegalStateException.class);
        verify(runService, never()).runManually();
    }
}
