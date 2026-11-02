package com.household.manager.charging.forecast;

import com.household.manager.repository.ChargingObservationRepository;
import com.household.manager.repository.ChargingOccupancyHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Raeumt die Belegungshistorie nachts auf. Bewusst ohne aeussere Transaktion: die Bulk-Deletes
 * bringen ihre eigene mit, und beide Pfade bleiben so unabhaengig voneinander.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChargingHistoryRetentionJob {

    private final ChargingOccupancyHistoryRepository history;
    private final ChargingObservationRepository observations;
    private final Clock clock;

    @Scheduled(cron = "0 50 3 * * *")
    public void purge() {
        Instant cutoff = clock.instant().minus(ChargingForecastSettings.RETENTION);
        try {
            int deleted = history.deleteEndedBefore(cutoff);
            log.debug("Ladesaeulen-Retention: {} Belegungen geloescht", deleted);
        } catch (Exception e) {
            log.warn("Retention der Belegungshistorie fehlgeschlagen", e);
        }
        try {
            int deleted = observations.deleteHoursBefore(cutoff);
            log.debug("Ladesaeulen-Retention: {} Beobachtungsstunden geloescht", deleted);
        } catch (Exception e) {
            log.warn("Retention der Beobachtungszeit fehlgeschlagen", e);
        }
    }
}
