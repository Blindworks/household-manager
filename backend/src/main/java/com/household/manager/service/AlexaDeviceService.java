package com.household.manager.service;

import com.household.manager.alexa.AlexaApiClient;
import com.household.manager.alexa.AlexaAuthService;
import com.household.manager.alexa.AlexaRemoteDevice;
import com.household.manager.alexa.AlexaSession;
import com.household.manager.model.entity.AlexaDevice;
import com.household.manager.repository.AlexaDeviceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Persistiert die Echo-Geraete des Kontos. Rescan legt neue an und aktualisiert
 * vorhandene ueber die stabile serialNumber; es wird nichts automatisch geloescht.
 */
@Service
@Slf4j
public class AlexaDeviceService {

    private final AlexaDeviceRepository repository;
    private final AlexaApiClient apiClient;
    private final AlexaAuthService authService;

    public AlexaDeviceService(AlexaDeviceRepository repository,
                              AlexaApiClient apiClient,
                              AlexaAuthService authService) {
        this.repository = repository;
        this.apiClient = apiClient;
        this.authService = authService;
    }

    public List<AlexaDevice> getDevices() {
        return repository.findAll();
    }

    /** Holt die aktuelle Geraeteliste aus der Cloud und synchronisiert die DB. */
    public List<AlexaDevice> rescan() {
        AlexaSession session = authService.getValidSession();
        List<AlexaRemoteDevice> remotes = apiClient.listDevices(session);
        for (AlexaRemoteDevice remote : remotes) {
            AlexaDevice device = repository.findBySerialNumber(remote.serialNumber())
                    .orElseGet(() -> AlexaDevice.builder()
                            .serialNumber(remote.serialNumber())
                            .build());
            device.setName(remote.accountName());
            device.setDeviceType(remote.deviceType());
            device.setTtsCapable(remote.isTtsCapable());
            repository.save(device);
        }
        log.info("Alexa-Rescan: {} Geraete aus der Cloud verarbeitet", remotes.size());
        return getDevices();
    }
}
