package com.household.manager.service;

import com.household.manager.dto.WeatherConditions;
import com.household.manager.dto.WeatherOverviewResponse;
import com.household.manager.dto.WeatherPollingStatusResponse;
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

/** Pollt DWD-Wetter und persistiert einen Ist-Bedingungen-Snapshot. */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeatherPollingService {

    private static final String SCHEDULE = "Alle 15 Minuten";

    private final DwdWeatherService dwdWeatherService;
    private final WeatherReadingRepository repository;
    private final TaskScheduler taskScheduler;

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
            lastError = null;
            log.debug("Saved weather reading at {}", current.getTime());
        } catch (Exception ex) {
            lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.error("Failed to poll DWD weather", ex);
        }
    }
}
