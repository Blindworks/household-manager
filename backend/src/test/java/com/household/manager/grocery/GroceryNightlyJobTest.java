package com.household.manager.grocery;

import com.household.manager.exception.TooManyRequestsException;
import com.household.manager.grocery.dto.RunResultDto;
import com.household.manager.systemlog.SystemJobMonitor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroceryNightlyJobTest {

    private final GroceryRunService runService = mock(GroceryRunService.class);
    private final GroceryProperties properties = new GroceryProperties();
    private final SystemJobMonitor monitor = mock(SystemJobMonitor.class);
    private final GroceryNightlyJob job = new GroceryNightlyJob(runService, properties, monitor);

    @Test
    void disabledReportsInactive() {
        properties.setEnabled(false);

        job.run();

        verify(monitor).reportInactive("deaktiviert");
        verify(runService, never()).run();
    }

    @Test
    void sourceFailuresAndDeferredProductsAreReported() {
        Map<String, String> failures = new LinkedHashMap<>();
        failures.put("REWE", "Zugriff gesperrt (HTTP 403)");
        failures.put("PENNY", "HTTP 500");
        when(runService.run()).thenReturn(new RunResultDto(10, 50, 3, 2, failures));

        job.run();

        verify(monitor).reportFailure("Quellen gestört: REWE (Zugriff gesperrt (HTTP 403)), PENNY (HTTP 500); "
                + "Claude-Deckel erreicht, 3 Produkte verschoben");
    }

    @Test
    void cleanRunReportsNothing() {
        when(runService.run()).thenReturn(new RunResultDto(10, 5, 0, 2, Map.of()));

        job.run();

        verify(monitor, never()).reportFailure(anyString());
    }

    @Test
    void overlappingManualRunIsSkippedSilently() {
        when(runService.run()).thenThrow(new TooManyRequestsException("Lauf läuft bereits"));

        job.run();

        verify(monitor, never()).reportFailure(anyString());
    }

    @Test
    void unexpectedErrorIsReported() {
        when(runService.run()).thenThrow(new RuntimeException("DB weg"));

        job.run();

        verify(monitor).reportFailure("DB weg");
    }
}
