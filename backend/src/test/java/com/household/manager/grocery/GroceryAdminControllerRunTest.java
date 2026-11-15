package com.household.manager.grocery;

import com.household.manager.exception.TooManyRequestsException;
import com.household.manager.grocery.dto.RunStartedDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GroceryAdminControllerRunTest {

    private final GroceryRunService runService = mock(GroceryRunService.class);
    private final GroceryProperties properties = new GroceryProperties();
    private final GroceryAdminController controller =
            new GroceryAdminController(null, null, null, properties, null, runService);

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
