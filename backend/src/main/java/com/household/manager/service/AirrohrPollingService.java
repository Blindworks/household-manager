package com.household.manager.service;

import com.household.manager.dto.AirrohrPollingStatusResponse;
import com.household.manager.dto.AirrohrReadingResponse;
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
            lastError = null;
            log.debug("Saved Airrohr reading at {}", response.getReadingTime());
        } catch (Exception ex) {
            lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.error("Failed to poll Airrohr sensor", ex);
        }
    }
}
