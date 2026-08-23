package com.household.manager.tractive;

import com.household.manager.model.entity.TractivePosition;
import com.household.manager.repository.TractivePositionRepository;
import com.household.manager.tractive.dto.TractivePositionDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Schreibt die Positionen mit, die der Poll-Zyklus ohnehin abruft.
 * <p>
 * Die Tractive-Cloud liefert beim Basic-Abo nur rund 24 Stunden Historie —
 * laengere Zeitraeume entstehen ausschliesslich dadurch, dass wir selbst
 * mitschreiben. Das kostet keinen einzigen zusaetzlichen Cloud-Aufruf: der
 * Poller hat die Position bereits in der Hand.
 * <p>
 * <b>Gespeichert wird nur ein NEUER Bericht.</b> Bei ausgeschaltetem Tracker
 * liefert die API weiter die letzte bekannte Position mit unveraendertem
 * Zeitstempel. Wuerde die jede Minute erneut gespeichert, entstuende ein
 * kuenstlich lueckenloser Strom — und der TractiveWalkDetector erkennt
 * Spaziergaenge gerade an den Funkpausen ueber 30 Minuten. Das Ergebnis waere
 * ein einziger, nie endender Spaziergang.
 * <p>
 * <b>Wirft nie.</b> Derselbe Poll versorgt die Entitaeten, die Dashboard-Kachel
 * und den Zu-Hause-Sensor; ein Historie-Fehler darf das nicht mitreissen
 * (Muster von PowerHistoryRecorder und AuditService).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TractivePositionRecorder {

    private final TractivePositionRepository repository;

    public void record(List<TractivePetSnapshot> snapshots) {
        for (TractivePetSnapshot snapshot : snapshots) {
            try {
                recordOne(snapshot);
            } catch (Exception ex) {
                // Bewusst je Tier gefangen: ein kaputter Tracker darf die anderen
                // nicht um ihren Eintrag bringen.
                log.warn("Position von {} nicht speicherbar: {}",
                        snapshot.trackerId(), ex.getMessage());
            }
        }
    }

    private void recordOne(TractivePetSnapshot snapshot) {
        TractivePositionDto position = snapshot.position();
        if (position == null || !position.hasCoordinates()) {
            return;
        }
        Instant reportedAt = position.reportedAt();
        if (reportedAt == null) {
            // Ein geratener Zeitstempel wuerde die Luecken verfaelschen, an denen
            // der Detektor die Runden trennt.
            return;
        }
        String trackerId = snapshot.trackerId();
        if (repository.existsByTrackerIdAndPositionTime(trackerId, reportedAt)) {
            return;
        }
        repository.save(TractivePosition.builder()
                .trackerId(trackerId)
                .positionTime(reportedAt)
                .latitude(position.latitude())
                .longitude(position.longitude())
                .accuracy(position.accuracy())
                .sensorUsed(position.sensorUsed())
                .build());
    }
}
