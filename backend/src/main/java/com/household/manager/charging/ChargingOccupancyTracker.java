package com.household.manager.charging;

import com.household.manager.model.entity.ChargingPointOccupancy;
import com.household.manager.repository.ChargingPointOccupancyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
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

    /** Zuletzt gesehener Status je Ladepunkt (nur im Speicher; entscheidet "Beginn bekannt?"). */
    private final Map<String, ChargePointStatus> lastSeen = new HashMap<>();

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
                    }
                }
                case FREE, OUT_OF_SERVICE -> {
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
