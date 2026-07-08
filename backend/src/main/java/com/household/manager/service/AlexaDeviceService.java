package com.household.manager.service;

import com.household.manager.alexa.AlexaSidecarClient;
import com.household.manager.alexa.AlexaSidecarClient.SidecarDevice;
import com.household.manager.model.entity.AlexaDevice;
import com.household.manager.repository.AlexaDeviceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Persistiert die Echo-Geraete des Kontos. Rescan holt die Liste vom Sidecar, legt neue an und
 * aktualisiert vorhandene ueber die stabile serialNumber; es wird nichts automatisch geloescht.
 */
@Service
@Slf4j
public class AlexaDeviceService {

    private final AlexaDeviceRepository repository;
    private final AlexaSidecarClient sidecarClient;

    public AlexaDeviceService(AlexaDeviceRepository repository, AlexaSidecarClient sidecarClient) {
        this.repository = repository;
        this.sidecarClient = sidecarClient;
    }

    public List<AlexaDevice> getDevices() {
        return repository.findAll();
    }

    /** Holt die aktuelle Geraeteliste vom Sidecar und synchronisiert die DB. */
    public List<AlexaDevice> rescan() {
        List<SidecarDevice> remotes = sidecarClient.getDevices();
        for (SidecarDevice remote : remotes) {
            AlexaDevice device = repository.findBySerialNumber(remote.serialNumber())
                    .orElseGet(() -> AlexaDevice.builder()
                            .serialNumber(remote.serialNumber())
                            .build());
            device.setName(remote.name());
            device.setDeviceType(remote.deviceType());
            device.setTtsCapable(remote.ttsCapable());
            repository.save(device);
        }
        log.info("Alexa-Rescan: {} Geraete vom Sidecar verarbeitet", remotes.size());
        return getDevices();
    }
}
