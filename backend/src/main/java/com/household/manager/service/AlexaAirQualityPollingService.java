package com.household.manager.service;

import com.household.manager.alexa.AlexaSidecarClient;
import com.household.manager.alexa.AlexaSidecarClient.SidecarAirQualityState;
import com.household.manager.dto.AlexaAirQualityPollingStatusResponse;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.AlexaAirQualityReading;
import com.household.manager.repository.AlexaAirQualityReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pollt die Amazon Smart Air Quality Monitore ueber den Alexa-Sidecar,
 * persistiert die Messwerte und meldet sie an die Entity-State-Schicht.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlexaAirQualityPollingService {

    private static final String SCHEDULE = "Alle 5 Minuten";

    private final AlexaSidecarClient sidecarClient;
    private final AlexaAirQualityReadingRepository repository;
    private final TaskScheduler taskScheduler;
    private final EntityStateService entityStateService;

    private volatile LocalDateTime lastPollTime;
    private volatile String lastError;

    public AlexaAirQualityPollingStatusResponse getStatus() {
        return AlexaAirQualityPollingStatusResponse.builder()
                .schedule(SCHEDULE)
                .lastPollTime(lastPollTime)
                .lastError(lastError)
                .build();
    }

    public void triggerOnce() {
        taskScheduler.schedule(this::safePoll, Instant.now());
    }

    @Scheduled(
            fixedDelayString = "${alexa.air-quality.polling.interval-ms:300000}",
            initialDelayString = "${alexa.air-quality.polling.initial-delay-ms:20000}"
    )
    public void scheduledPoll() {
        safePoll();
    }

    private void safePoll() {
        lastPollTime = LocalDateTime.now();
        try {
            List<SidecarAirQualityState> states = sidecarClient.getAirQualityStates();
            LocalDateTime readingTime = LocalDateTime.now();
            for (SidecarAirQualityState state : states) {
                repository.save(toReading(state, readingTime));
                reportEntityStates(state);
            }
            lastError = null;
            log.debug("Saved {} Alexa air quality readings", states.size());
        } catch (Exception ex) {
            lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.error("Failed to poll Alexa air quality monitors", ex);
        }
    }

    private AlexaAirQualityReading toReading(SidecarAirQualityState state, LocalDateTime readingTime) {
        return AlexaAirQualityReading.builder()
                .applianceId(state.applianceId())
                .deviceName(state.friendlyName())
                .readingTime(readingTime)
                .iaq(state.iaq())
                .pm25(state.pm25())
                .voc(state.voc())
                .co(state.co())
                .temperature(state.temperature())
                .humidity(state.humidity())
                .build();
    }

    private void reportEntityStates(SidecarAirQualityState state) {
        try {
            reportSensor(state, "iaq", "IAQ", state.iaq(), null, "aqi");
            reportSensor(state, "pm25", "PM2.5", state.pm25(), "µg/m³", "pm25");
            reportSensor(state, "voc", "VOC", state.voc(), "ppb", "volatile_organic_compounds_parts");
            reportSensor(state, "co", "CO", state.co(), "ppm", "carbon_monoxide");
            reportSensor(state, "temperature", "Temperatur", state.temperature(), "°C", "temperature");
            reportSensor(state, "humidity", "Luftfeuchte", state.humidity(), "%", "humidity");
        } catch (Exception ex) {
            log.warn("Failed to report alexa air quality entity states: {}", ex.getMessage());
        }
    }

    private void reportSensor(SidecarAirQualityState state, String suffix, String label,
                               Object value, String unit, String deviceClass) {
        if (value == null) {
            return;
        }
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("deviceClass", deviceClass);
        if (unit != null) {
            attributes.put("unit", unit);
        }
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.ALEXA, state.applianceId(), suffix))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.ALEXA)
                .sourceRef(state.applianceId())
                .friendlyName(state.friendlyName() + " " + label)
                .state(String.valueOf(value))
                .attributes(attributes)
                .build());
    }
}
