package com.household.manager.shelly;

import com.household.manager.model.entity.ShellyReading;
import com.household.manager.repository.ShellyReadingRepository;
import com.household.manager.shelly.dto.ShellyStatusDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShellyPollingService {

    private final ShellyService shellyService;
    private final ShellyReadingRepository shellyReadingRepository;

    @Scheduled(fixedDelayString = "${shelly.polling.interval-ms:60000}")
    public void pollAllDevices() {
        //log.debug("Polling Shelly devices...");
        List<ShellyStatusDto> statuses = shellyService.getAllDevicesStatus();

        for (ShellyStatusDto status : statuses) {
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
}
