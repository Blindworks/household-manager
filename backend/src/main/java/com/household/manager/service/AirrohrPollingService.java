package com.household.manager.service;

import com.household.manager.dto.AirrohrPollingStatusResponse;
import com.household.manager.dto.AirrohrReadingResponse;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.AirrohrReading;
import com.household.manager.repository.AirrohrReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Polls Airrohr data and persists readings in the database.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AirrohrPollingService {

    private static final String SCHEDULE = "Alle 5 Minuten";

    private final AirrohrService airrohrService;
    private final AirrohrReadingRepository airrohrReadingRepository;
    private final TaskScheduler taskScheduler;
    private final EntityStateService entityStateService;

    @Value("${airrohr.url}")
    private String airrohrUrl;

    private volatile LocalDateTime lastPollTime;
    private volatile String lastError;

    public AirrohrPollingStatusResponse getStatus() {
        return AirrohrPollingStatusResponse.builder()
                .url(airrohrUrl)
                .schedule(SCHEDULE)
                .lastPollTime(lastPollTime)
                .lastError(lastError)
                .build();
    }

    public void triggerOnce() {
        taskScheduler.schedule(this::safePoll, Instant.now());
    }

    @Scheduled(
            fixedDelayString = "${airrohr.polling.interval-ms:300000}",
            initialDelayString = "${airrohr.polling.initial-delay-ms:15000}"
    )
    public void scheduledPoll() {
        safePoll();
    }

    private void safePoll() {
        try {
            lastPollTime = LocalDateTime.now();
            AirrohrReadingResponse response = airrohrService.getCurrentReading();

            AirrohrReading entity = AirrohrReading.builder()
                    .readingTime(response.getReadingTime())
                    .softwareVersion(response.getSoftwareVersion())
                    .ageSeconds(response.getAgeSeconds())
                    .sdsP1(response.getSdsP1())
                    .sdsP2(response.getSdsP2())
                    .build();

            airrohrReadingRepository.save(entity);
            reportEntityStates(response);
            lastError = null;
            log.debug("Saved Airrohr reading at {}", response.getReadingTime());
        } catch (Exception ex) {
            lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.error("Failed to poll Airrohr sensor", ex);
        }
    }

    private void reportEntityStates(AirrohrReadingResponse response) {
        try {
            reportSensor("pm10", "Feinstaub PM10", response.getSdsP1(), "pm10");
            reportSensor("pm25", "Feinstaub PM2.5", response.getSdsP2(), "pm25");
        } catch (Exception ex) {
            log.warn("Failed to report airrohr entity states: {}", ex.getMessage());
        }
    }

    private void reportSensor(String suffix, String label, Object value, String deviceClass) {
        if (value == null) {
            return;
        }
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.AIRROHR, "airrohr", suffix))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.AIRROHR)
                .sourceRef("airrohr")
                .friendlyName("Airrohr " + label)
                .state(String.valueOf(value))
                .attributes(Map.of("unit", "µg/m³", "deviceClass", deviceClass))
                .build());
    }
}
