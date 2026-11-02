package com.household.manager.charging.forecast;

import com.household.manager.model.entity.ChargingObservation;
import com.household.manager.model.entity.ChargingOccupancyRecord;
import com.household.manager.repository.ChargingObservationRepository;
import com.household.manager.repository.ChargingOccupancyHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Schreibt abgeschlossene Belegungen und beobachtete Zeit fuer die Vorhersage. Wirft nie:
 * ein Fehler hier darf den Poll und die Entitaetsmeldung nicht kippen.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChargingHistoryRecorder {

    private final ChargingOccupancyHistoryRepository history;
    private final ChargingObservationRepository observations;

    public void recordEnded(String stationId, String chargePointId, Instant startedAt, Instant endedAt) {
        try {
            // DATETIME kennt keine Bruchsekunden
            Instant start = startedAt.truncatedTo(ChronoUnit.SECONDS);
            Instant end = endedAt.truncatedTo(ChronoUnit.SECONDS);
            if (!end.isAfter(start)) {
                return;
            }
            history.save(ChargingOccupancyRecord.builder()
                    .stationId(stationId)
                    .chargePointId(chargePointId)
                    .startedAt(start)
                    .endedAt(end)
                    .build());
        } catch (Exception e) {
            log.warn("Belegung von {}/{} konnte nicht aufgezeichnet werden", stationId, chargePointId, e);
        }
    }

    public void recordObservation(String stationId, Instant polledAt, int observedSeconds,
                                  int freePoints, int occupiedPoints) {
        try {
            Instant hour = polledAt.truncatedTo(ChronoUnit.HOURS);
            ChargingObservation bucket = observations.findByStationIdAndHourStart(stationId, hour)
                    .orElseGet(() -> ChargingObservation.builder()
                            .stationId(stationId).hourStart(hour).build());
            bucket.setObservedSeconds(bucket.getObservedSeconds() + observedSeconds);
            bucket.setFreePointSeconds(bucket.getFreePointSeconds() + (long) freePoints * observedSeconds);
            bucket.setOccupiedPointSeconds(
                    bucket.getOccupiedPointSeconds() + (long) occupiedPoints * observedSeconds);
            observations.save(bucket);
        } catch (Exception e) {
            log.warn("Beobachtungszeit von {} konnte nicht aufgezeichnet werden", stationId, e);
        }
    }
}
