package com.household.manager.shelly;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.ShellyReading;
import com.household.manager.repository.ShellyReadingRepository;
import com.household.manager.shelly.dto.ShellyStatusDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShellyPollingService {

    private final ShellyService shellyService;
    private final ShellyReadingRepository shellyReadingRepository;
    private final EntityStateService entityStateService;

    @Scheduled(fixedDelayString = "${shelly.polling.interval-ms:60000}")
    public void pollAllDevices() {
        //log.debug("Polling Shelly devices...");
        List<ShellyStatusDto> statuses = shellyService.getAllDevicesStatus();

        for (ShellyStatusDto status : statuses) {
            reportEntityStates(status);
            if (!status.reachable()) {
                log.warn("Shelly '{}' is unreachable, skipping persist", status.deviceName());
                continue;
            }
            ShellyReading reading = ShellyReading.builder()
                    .deviceName(status.deviceName())
                    .timestamp(LocalDateTime.now())
                    .power(status.power())
                    .voltage(status.voltage())
                    .currentA(status.current())
                    .totalEnergy(status.totalEnergy())
                    .build();
            shellyReadingRepository.save(reading);
            //log.debug("Saved reading for Shelly '{}': {}W", status.deviceName(), status.power());
        }
    }

    private void reportEntityStates(ShellyStatusDto status) {
        try {
            String powerState = status.reachable() && status.power() != null
                    ? String.valueOf(status.power()) : "unavailable";
            String energyState = status.reachable() && status.totalEnergy() != null
                    ? String.valueOf(status.totalEnergy()) : "unavailable";

            reportSensor(status.deviceName(), "power", "Leistung", powerState, Map.of("unit", "W", "deviceClass", "power"));
            reportSensor(status.deviceName(), "energy", "Gesamtenergie", energyState, Map.of("unit", "kWh", "deviceClass", "energy"));
        } catch (Exception ex) {
            log.warn("Failed to report shelly entity states for {}: {}", status.deviceName(), ex.getMessage());
        }
    }

    private void reportSensor(String deviceName, String suffix, String label, String state, Map<String, Object> attributes) {
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.SHELLY, deviceName, suffix))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.SHELLY)
                .sourceRef(deviceName)
                .friendlyName(deviceName + " " + label)
                .state(state)
                .attributes(attributes)
                .build());
    }
}
