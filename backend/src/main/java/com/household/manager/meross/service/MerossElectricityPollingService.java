package com.household.manager.meross.service;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.meross.config.MerossElectricityPollingProperties;
import com.household.manager.meross.dto.MerossElectricityReading;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Pollt den Momentanverbrauch konfigurierter Meross-Steckdosen und spiegelt ihn
 * als {@code sensor.meross_<uuid>_power} in die Entity-State-Schicht (Basis für
 * Flow-Trigger wie „Waschmaschine fertig").
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MerossElectricityPollingService {

    private final MerossElectricityPollingProperties properties;
    private final MerossDeviceService merossDeviceService;
    private final EntityStateService entityStateService;

    @Scheduled(fixedDelayString = "${meross.electricity.polling.interval-ms:60000}",
            initialDelayString = "${meross.electricity.polling.initial-delay-ms:20000}")
    public void poll() {
        if (!properties.isEnabled() || properties.getDeviceIds().isEmpty()) {
            return;
        }
        for (String deviceId : properties.getDeviceIds()) {
            pollDevice(deviceId);
        }
    }

    private void pollDevice(String deviceId) {
        try {
            reportPowerState(merossDeviceService.readElectricity(deviceId));
        } catch (Exception ex) {
            // Bewusst kein 'unavailable'-Report: ein transienter Lesefehler soll keinen
            // Zustandsübergang erzeugen, sonst feuern flankengetriggerte Flows fehl.
            log.warn("Meross electricity poll failed (deviceId={}): {}", deviceId, ex.getMessage());
        }
    }

    private void reportPowerState(MerossElectricityReading reading) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("unit", "W");
        attributes.put("deviceClass", "power");
        if (reading.voltageVolts() != null) {
            attributes.put("voltage", reading.voltageVolts());
        }
        if (reading.currentAmps() != null) {
            attributes.put("current", reading.currentAmps());
        }
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.MEROSS, reading.deviceId(), "power"))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.MEROSS)
                .sourceRef(reading.deviceId())
                .friendlyName(reading.deviceName() + " Leistung")
                .state(reading.powerWatts().toPlainString())
                .attributes(attributes)
                .build());
    }
}
