package com.household.manager.service;

import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Pollt zyklisch den An/Aus-Zustand aller lokalen Smart Plugs (Kasa und Tapo) und
 * spiegelt ihn über {@link SmartDeviceService#refreshDeviceState(Long)} in die
 * Entity-State-Schicht. Ohne diesen Poller aktualisiert der gespiegelte Zustand,
 * den die Schalter-Kachel und Flows lesen, nur bei einem Seitenaufruf, einem Scan
 * oder einem Schaltbefehl über die App – eine extern (Tapo-/Kasa-App, Wandschalter)
 * ausgelöste Änderung bliebe sonst unsichtbar.
 *
 * <p><strong>Meross ist bewusst ausgenommen:</strong> ein An/Aus-Poll pro Meross-Plug
 * kostet einen MQTT-Connect je Zyklus (siehe {@code MerossDeviceService#getStatus} →
 * {@code readOnStateViaMqtt}); das über alle Steckdosen minütlich wäre ein
 * Verbindungssturm zusätzlich zum bestehenden Verbrauchs-Poller. Kasa/Tapo sind rein
 * lokal und kosten nur einen TCP-/KLAP-Roundtrip. Meross-An/Aus wäre eine eigene,
 * teurere Entscheidung.
 */
@Service
@Slf4j
public class SmartDeviceStatePollingService {

    private static final List<DeviceType> POLLED_TYPES = List.of(DeviceType.KASA, DeviceType.TAPO);

    private final SmartDeviceRepository smartDeviceRepository;
    private final SmartDeviceService smartDeviceService;
    private final boolean enabled;

    public SmartDeviceStatePollingService(
            SmartDeviceRepository smartDeviceRepository,
            SmartDeviceService smartDeviceService,
            @Value("${smart-device.state-polling.enabled:true}") boolean enabled) {
        this.smartDeviceRepository = smartDeviceRepository;
        this.smartDeviceService = smartDeviceService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${smart-device.state-polling.interval-ms:60000}",
            initialDelayString = "${smart-device.state-polling.initial-delay-ms:25000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        for (SmartDevice device : smartDeviceRepository.findByDeviceTypeInOrderByDeviceNameAsc(POLLED_TYPES)) {
            try {
                smartDeviceService.refreshDeviceState(device.getId());
            } catch (Exception ex) {
                // Ein nicht erreichbares Gerät darf die anderen nicht mit abbrechen. refreshDeviceState
                // markiert es bei einem Kommunikationsfehler selbst als offline; nur debug loggen, sonst
                // füllt ein dauerhaft ausgestecktes Gerät jede Minute das Log.
                log.debug("Zustands-Poll für Gerät {} ({}) fehlgeschlagen: {}",
                        device.getId(), device.getDeviceName(), ex.getMessage());
            }
        }
    }
}
