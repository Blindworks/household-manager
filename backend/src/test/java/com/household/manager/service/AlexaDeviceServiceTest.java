package com.household.manager.service;

import com.household.manager.alexa.AlexaSidecarClient;
import com.household.manager.alexa.AlexaSidecarClient.SidecarDevice;
import com.household.manager.model.entity.AlexaDevice;
import com.household.manager.repository.AlexaDeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AlexaDeviceServiceTest {

    private AlexaDeviceRepository repository;
    private AlexaSidecarClient sidecarClient;
    private AlexaDeviceService service;

    @BeforeEach
    void setUp() {
        repository = mock(AlexaDeviceRepository.class);
        sidecarClient = mock(AlexaSidecarClient.class);
        service = new AlexaDeviceService(repository, sidecarClient);

        when(repository.save(any(AlexaDevice.class))).thenAnswer(i -> i.getArgument(0));
    }

    private SidecarDevice remote(String serial, String name, boolean tts) {
        return new SidecarDevice(serial, name, "A1TYPE", tts);
    }

    @Test
    void rescanInsertsNewDevice() {
        when(sidecarClient.getDevices()).thenReturn(List.of(remote("DSN1", "Kueche", true)));
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
        when(sidecarClient.getDevices()).thenReturn(List.of(remote("DSN1", "Neu", true)));
        when(repository.findBySerialNumber("DSN1")).thenReturn(Optional.of(existing));

        service.rescan();

        verify(repository).save(argThat(d -> d.getName().equals("Neu")));
    }

    @Test
    void rescanDoesNotDeleteDevicesMissingFromCloud() {
        when(sidecarClient.getDevices()).thenReturn(new ArrayList<>());

        service.rescan();

        verify(repository, never()).delete(any());
        verify(repository, never()).deleteAll();
    }
}
