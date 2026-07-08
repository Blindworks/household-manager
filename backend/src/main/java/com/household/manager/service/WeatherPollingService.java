package com.household.manager.service;

import com.household.manager.dto.WeatherConditions;
import com.household.manager.dto.WeatherOverviewResponse;
import com.household.manager.dto.WeatherPollingStatusResponse;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.WeatherReading;
import com.household.manager.repository.WeatherReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

/** Pollt DWD-Wetter und persistiert einen Ist-Bedingungen-Snapshot. */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeatherPollingService {

    private static final String SCHEDULE = "Alle 15 Minuten";

    private final DwdWeatherService dwdWeatherService;
    private final WeatherReadingRepository repository;
    private final TaskScheduler taskScheduler;
    private final EntityStateService entityStateService;

    @Value("${dwd.station-id}")
    private String stationId;

    private volatile LocalDateTime lastPollTime;
    private volatile String lastError;

    public WeatherPollingStatusResponse getStatus() {
        return WeatherPollingStatusResponse.builder()
                .stationId(stationId)
                .schedule(SCHEDULE)
                .lastPollTime(lastPollTime)
                .lastError(lastError)
                .build();
    }

    public void triggerOnce() {
        taskScheduler.schedule(this::safePoll, Instant.now());
    }

    @Scheduled(
            fixedDelayString = "${dwd.polling.interval-ms:900000}",
            initialDelayString = "${dwd.polling.initial-delay-ms:20000}"
    )
    public void scheduledPoll() {
        safePoll();
    }

    private void safePoll() {
        try {
            lastPollTime = LocalDateTime.now();
            WeatherOverviewResponse overview = dwdWeatherService.getOverview();
            WeatherConditions current = overview.getCurrent();
            if (current == null) {
                throw new IllegalStateException("DWD overview has no current conditions.");
            }

            WeatherReading entity = WeatherReading.builder()
                    .readingTime(current.getTime())
                    .temperature(current.getTemperature())
                    .precipitation(current.getPrecipitation())
                    .windSpeed(current.getWindSpeed())
                    .windDirection(current.getWindDirection())
                    .humidity(current.getHumidity())
                    .pressure(current.getPressure())
                    .icon(current.getIcon())
                    .build();

            repository.save(entity);
            reportEntityStates(current);
            lastError = null;
            log.debug("Saved weather reading at {}", current.getTime());
        } catch (Exception ex) {
            lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.error("Failed to poll DWD weather", ex);
        }
    }

    private void reportEntityStates(WeatherConditions current) {
        try {
            reportSensor("temperature", "Temperatur", current.getTemperature(), "°C", "temperature");
            reportSensor("humidity", "Luftfeuchtigkeit", current.getHumidity(), "%", "humidity");
            reportSensor("precipitation", "Niederschlag", current.getPrecipitation(), "mm", "precipitation");
            reportSensor("wind_speed", "Windgeschwindigkeit", current.getWindSpeed(), "km/h", "wind_speed");
            reportSensor("pressure", "Luftdruck", current.getPressure(), "hPa", "pressure");
        } catch (Exception ex) {
            log.warn("Failed to report weather entity states: {}", ex.getMessage());
        }
    }

    private void reportSensor(String suffix, String label, Object value, String unit, String deviceClass) {
        if (value == null) {
            return;
        }
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.WEATHER, "dwd", suffix))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.WEATHER)
                // sourceRef entspricht dem ID-Segment (Invariante aller Quellen); die
                // konkrete Station steht im Attribut "stationId".
                .sourceRef("dwd")
                .friendlyName("Wetter " + label)
                .state(String.valueOf(value))
                .attributes(Map.of("unit", unit, "deviceClass", deviceClass, "stationId", stationId))
                .build());
    }
}
