package com.household.manager.service;

import com.household.manager.alexa.AlexaApiClient;
import com.household.manager.alexa.AlexaAuthService;
import com.household.manager.alexa.AlexaRemoteDevice;
import com.household.manager.alexa.AlexaSession;
import com.household.manager.model.entity.AlexaDevice;
import com.household.manager.repository.AlexaDeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AlexaDeviceServiceTest {

    private AlexaDeviceRepository repository;
    private AlexaApiClient apiClient;
    private AlexaAuthService authService;
    private AlexaDeviceService service;

    @BeforeEach
    void setUp() {
        repository = mock(AlexaDeviceRepository.class);
        apiClient = mock(AlexaApiClient.class);
        authService = mock(AlexaAuthService.class);
        service = new AlexaDeviceService(repository, apiClient, authService);

        when(authService.getValidSession()).thenReturn(mock(AlexaSession.class));
        when(repository.save(any(AlexaDevice.class))).thenAnswer(i -> i.getArgument(0));
    }

    private AlexaRemoteDevice remote(String serial, String name, boolean tts) {
        return new AlexaRemoteDevice(serial, name, "A1TYPE", "ROOK",
                tts ? List.of("AUDIO_PLAYER") : List.of());
    }

    @Test
    void rescanInsertsNewDevice() {
        when(apiClient.listDevices(any())).thenReturn(List.of(remote("DSN1", "Kueche", true)));
        when(repository.findBySerialNumber("DSN1")).thenReturn(Optional.empty());

        service.rescan();

        verify(repository).save(argThat(d ->
                d.getSerialNumber().equals("DSN1")
                        && d.getName().equals("Kueche")
                        && d.isTtsCapable()));
    }

    @Test
    void rescanUpdatesExistingDeviceName() {
        AlexaDevice existing = AlexaDevice.builder()
                .serialNumber("DSN1").name("Alt").deviceType("A1TYPE").ttsCapable(true).build();
        when(apiClient.listDevices(any())).thenReturn(List.of(remote("DSN1", "Neu", true)));
        when(repository.findBySerialNumber("DSN1")).thenReturn(Optional.of(existing));

        service.rescan();

        verify(repository).save(argThat(d -> d.getName().equals("Neu")));
    }

    @Test
    void rescanDoesNotDeleteDevicesMissingFromCloud() {
        when(apiClient.listDevices(any())).thenReturn(new ArrayList<>());

        service.rescan();

        verify(repository, never()).delete(any());
        verify(repository, never()).deleteAll();
    }
}
