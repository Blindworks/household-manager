package com.household.manager.grocery;

import com.household.manager.exception.TooManyRequestsException;
import com.household.manager.grocery.dto.RunStartedDto;
import com.household.manager.grocery.dto.RunStatusDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroceryRunControllerTest {

    private final GroceryRunService runService = mock(GroceryRunService.class);
    private final GroceryProperties properties = new GroceryProperties();
    private final GroceryRunController controller = new GroceryRunController(runService, properties);

    @Test
    void acceptedRunAnswers202Started() {
        ResponseEntity<RunStartedDto> response = controller.runNow();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isEqualTo(new RunStartedDto("gestartet"));
        verify(runService).runManually();
    }

    @Test
    void runStatusComesFromTheRunService() {
        RunStatusDto status = new RunStatusDto(true, LocalDateTime.of(2026, 11, 16, 4, 0), null, null, null);
        when(runService.runStatus()).thenReturn(status);

        assertThat(controller.runStatus()).isEqualTo(status);
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
