package com.household.manager.shelly;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.repository.ShellyReadingRepository;
import com.household.manager.shelly.dto.ShellyStatusDto;
import com.household.manager.systemlog.SystemJobMonitor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShellyPollingServiceTest {

    private final ShellyService shellyService = mock(ShellyService.class);
    private final ShellyReadingRepository repository = mock(ShellyReadingRepository.class);
    private final EntityStateService entityStateService = mock(EntityStateService.class);
    private final SystemJobMonitor jobMonitor = mock(SystemJobMonitor.class);
    private final ShellyPollingService service =
            new ShellyPollingService(shellyService, repository, entityStateService, jobMonitor);

    private static ShellyStatusDto status(String name, boolean reachable) {
        return new ShellyStatusDto(name, "10.0.0.1", true, 10.0, 230.0, 0.1, 1.0, reachable);
    }

    @Test
    void poll_noDeviceReachable_reportsFailure() {
        when(shellyService.getAllDevicesStatus()).thenReturn(List.of(status("a", false), status("b", false)));

        service.pollAllDevices();

        verify(jobMonitor).reportFailure(anyString());
    }

    @Test
    void poll_oneDeviceUnreachable_doesNotReportFailure() {
        when(shellyService.getAllDevicesStatus()).thenReturn(List.of(status("a", false), status("b", true)));

        service.pollAllDevices();

        verify(jobMonitor, never()).reportFailure(anyString());
    }

    @Test
    void poll_noDevices_doesNotReportFailure() {
        when(shellyService.getAllDevicesStatus()).thenReturn(List.of());

        service.pollAllDevices();

        verify(jobMonitor, never()).reportFailure(anyString());
    }
}
