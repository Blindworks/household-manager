package com.household.manager.charging;

import com.household.manager.charging.forecast.ChargingForecastSettings;
import com.household.manager.charging.forecast.ChargingHistoryRecorder;
import com.household.manager.model.entity.ChargingPointOccupancy;
import com.household.manager.repository.ChargingPointOccupancyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Schreibt Belegungsuebergaenge je Ladepunkt. Regeln:
 * frei -> belegt (vorheriger Poll sah den Punkt frei): Zeile mit Beginn = jetzt;
 * erstmals belegt gesehen (kein Vorwissen, z. B. nach Neustart): Zeile OHNE Beginn - die
 * Anzeige sagt dann "seit mind."; belegt -> frei/ausser Betrieb: Zeile loeschen;
 * UNKNOWN: nichts anfassen (ein kurzer Statusaussetzer darf die Dauer nicht auf null setzen).
 */
@Service
@RequiredArgsConstructor
public class ChargingOccupancyTracker {

    private final ChargingPointOccupancyRepository repository;
    private final Clock clock;
    private final ChargingHistoryRecorder recorder;

    /** Zuletzt gesehener Status je Ladepunkt (nur im Speicher; entscheidet "Beginn bekannt?"). */
    private final Map<String, ChargePointStatus> lastSeen = new HashMap<>();

    /** Letzter record-Aufruf je Station (nur im Speicher; erkennt Luecken, z. B. nach Neustart). */
    private final Map<String, Instant> lastRecordedAt = new HashMap<>();

    public synchronized void record(ChargingStationDetails details) {
        Instant now = clock.instant();
        Map<String, ChargingPointOccupancy> existing = repository.findByStationId(details.stationId()).stream()
                .collect(Collectors.toMap(ChargingPointOccupancy::getChargePointId, Function.identity()));

        for (ChargePoint point : details.chargePoints()) {
            ChargingPointOccupancy row = existing.get(point.chargePointId());
            switch (point.status()) {
                case OCCUPIED -> {
                    if (row == null) {
                        Instant since = point.statusSince();
                        if (since == null) {
                            boolean beginKnown = lastSeen.get(point.chargePointId()) == ChargePointStatus.FREE;
                            since = beginKnown ? now : null;
                        }
                        repository.save(ChargingPointOccupancy.builder()
                                .chargePointId(point.chargePointId())
                                .stationId(details.stationId())
                                .occupiedSince(since)
                                .firstSeenOccupiedAt(now)
                                .build());
                    } else if (row.getOccupiedSince() == null && point.statusSince() != null) {
                        row.setOccupiedSince(point.statusSince());
                        repository.save(row);
                    } else if (startsNewSession(row, point)) {
                        // Zwischen zwei Polls endete eine Belegung und eine neue begann: das Ende der
                        // alten ist unbekannt - verwerfen statt beide zu einer langen zu verschmelzen
                        row.setOccupiedSince(point.statusSince());
                        row.setFirstSeenOccupiedAt(now);
                        repository.save(row);
                    }
                }
                case FREE -> {
                    if (row != null) {
                        recordEndedOccupancy(details.stationId(), point, row, now);
                        repository.delete(row);
                    }
                }
                case OUT_OF_SERVICE -> {
                    if (row != null) {
                        repository.delete(row);
                    }
                }
                case UNKNOWN -> {
                    // bewusst nichts
                }
            }
            if (point.status() != ChargePointStatus.UNKNOWN) {
                lastSeen.put(point.chargePointId(), point.status());
            }
        }
        lastRecordedAt.put(details.stationId(), now);
    }

    /** Beendete Belegung fuer die Vorhersage festhalten - nur mit belastbarem Beginn und Ende. */
    private void recordEndedOccupancy(String stationId, ChargePoint point, ChargingPointOccupancy row, Instant now) {
        Instant start = row.getOccupiedSince();
        if (start == null) {
            return;
        }
        Instant statusSince = point.statusSince();
        Instant previous = lastRecordedAt.get(stationId);
        boolean gap = previous != null && Duration.between(previous, now).compareTo(ChargingForecastSettings.MAX_GAP) > 0;
        if (statusSince != null && statusSince.isAfter(start)) {
            // Nach einer Luecke kann statusSince das Ende einer SPAETEREN Belegung sein (frei -> belegt -> frei
            // ungesehen); belastbar ist es nur bis zur Lueckengrenze hinter dem letzten Poll
            if (!gap || !statusSince.isAfter(previous.plus(ChargingForecastSettings.MAX_GAP))) {
                recorder.recordEnded(stationId, point.chargePointId(), start, statusSince);
            }
            return;
        }
        if (previous != null && !gap) {
            recorder.recordEnded(stationId, point.chargePointId(), start, now);
        }
        // sonst: Neustart oder Luecke - das Ende ist unbekannt, verwerfen statt falsch zu schaetzen
    }

    private static boolean startsNewSession(ChargingPointOccupancy row, ChargePoint point) {
        return row.getOccupiedSince() != null
                && point.statusSince() != null
                && point.statusSince().isAfter(row.getOccupiedSince());
    }

    public Map<String, ChargingPointOccupancy> occupancyFor(String stationId) {
        return repository.findByStationId(stationId).stream()
                .collect(Collectors.toMap(ChargingPointOccupancy::getChargePointId, Function.identity()));
    }

    /** Beim Entfernen eines Favoriten: Zeilen der Station verwerfen (kein FK, deshalb explizit). */
    public void forgetStation(String stationId) {
        repository.deleteByStationId(stationId);
    }
}
