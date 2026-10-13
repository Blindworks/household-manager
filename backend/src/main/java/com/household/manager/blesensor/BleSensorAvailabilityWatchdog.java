package com.household.manager.blesensor;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.BleSensor;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.BleSensorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Markiert die Entitaeten aktiver Sensoren ohne Meldung seit {@code ble-sensor.stale-after-minutes}
 * als unavailable — mit erhaltenen Attributen (aus den Letztwerten der Zeile), genau einmal je
 * Ausfall (eine schon unavailable Entitaet wird nicht erneut gemeldet). Wirft nie.
 */
@Component
@Slf4j
public class BleSensorAvailabilityWatchdog {

    private final BleSensorRepository repository;
    private final BleSensorEntityMapper mapper;
    private final EntityStateService entityStateService;
    private final Clock clock;
    private final int staleAfterMinutes;

    public BleSensorAvailabilityWatchdog(BleSensorRepository repository,
                                         BleSensorEntityMapper mapper,
                                         EntityStateService entityStateService,
                                         Clock clock,
                                         @Value("${ble-sensor.stale-after-minutes:10}") int staleAfterMinutes) {
        this.repository = repository;
        this.mapper = mapper;
        this.entityStateService = entityStateService;
        this.clock = clock;
        this.staleAfterMinutes = staleAfterMinutes;
    }

    @Scheduled(fixedDelayString = "${ble-sensor.watchdog-interval-ms:60000}")
    public void check() {
        try {
            LocalDateTime threshold = LocalDateTime.now(clock).minusMinutes(staleAfterMinutes);
            for (BleSensor sensor : repository.findByActiveTrue()) {
                if (sensor.getLastSeenAt() == null || !sensor.getLastSeenAt().isBefore(threshold)) {
                    continue;
                }
                markUnavailable(sensor);
            }
        } catch (Exception ex) {
            log.warn("BLE-Watchdog fehlgeschlagen: {}", ex.getMessage());
        }
    }

    private void markUnavailable(BleSensor sensor) {
        for (EntityStateUpdate update : mapper.unavailable(sensor)) {
            boolean alreadyUnavailable = entityStateService.getByEntityId(update.entityId())
                    .map(EntityState::getState)
                    .filter(BleSensorEntityMapper.STATE_UNAVAILABLE::equals)
                    .isPresent();
            if (alreadyUnavailable) {
                continue;
            }
            log.warn("BLE-Sensor '{}' ({}) sendet seit {} min nicht, Entitaet geht auf unavailable",
                    sensor.getName(), sensor.getMac(), staleAfterMinutes);
            entityStateService.reportState(update);
        }
    }
}
