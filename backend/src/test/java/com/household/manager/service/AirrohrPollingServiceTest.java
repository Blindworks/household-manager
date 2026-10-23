package com.household.manager.service;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.repository.AirrohrReadingRepository;
import com.household.manager.systemlog.SystemJobMonitor;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AirrohrPollingServiceTest {

    private final AirrohrService airrohrService = mock(AirrohrService.class);
    private final SystemJobMonitor jobMonitor = mock(SystemJobMonitor.class);
    private final AirrohrPollingService service = new AirrohrPollingService(
            airrohrService, mock(AirrohrReadingRepository.class), mock(TaskScheduler.class),
            mock(EntityStateService.class), jobMonitor);

    @Test
    void poll_sensorError_reportsFailure() {
        when(airrohrService.getCurrentReading()).thenThrow(new RuntimeException("Sensor weg"));

        service.scheduledPoll();

        verify(jobMonitor).reportFailure(contains("Sensor weg"));
    }
}
