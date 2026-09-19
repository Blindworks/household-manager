package com.household.manager.charging;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.ChargingFavorite;
import com.household.manager.repository.ChargingFavoriteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * Favorisierte Ladesaeulen. Name/Betreiber/Koordinaten kommen beim Favorisieren aus dem
 * aktuellen Snapshot, damit die Liste auch bei Quellenausfall lesbar bleibt.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChargingFavoriteService {

    private final ChargingFavoriteRepository repository;
    private final ChargingOccupancyTracker tracker;
    private final ChargingSnapshot snapshot;
    private final AuditService auditService;
    private final Clock clock;

    public List<ChargingFavorite> list() {
        return repository.findAllByOrderByCreatedAtAscIdAsc();
    }

    /** Nur eine Station aus dem aktuellen Umkreis-Stand ist favorisierbar (400 sonst). */
    @Transactional
    public void add(String stationId) {
        ChargingStation station = snapshot.station(stationId).orElseThrow(() ->
                new IllegalArgumentException("Die Station " + stationId
                        + " ist nicht in der aktuellen Umkreisliste und kann nicht favorisiert werden."));
        if (repository.existsByStationId(stationId)) {
            return;
        }
        repository.save(ChargingFavorite.builder()
                .stationId(stationId)
                .displayName(station.name())
                .operator(station.operator())
                .lat(station.lat())
                .lon(station.lon())
                .createdAt(clock.instant())
                .build());
        auditService.record("charging.favorite.add", stationId + " (" + station.name() + ")");
        log.info("Ladesaeule {} favorisiert", stationId);
    }

    /** Entfernt Favorit und Belegungszeilen; die Entitaet markiert der Poller beim naechsten Lauf. */
    @Transactional
    public void remove(String stationId) {
        repository.findByStationId(stationId).ifPresent(favorite -> {
            repository.delete(favorite);
            tracker.forgetStation(stationId);
            auditService.record("charging.favorite.remove", stationId + " (" + favorite.getDisplayName() + ")");
            log.info("Ladesaeule {} aus den Favoriten entfernt", stationId);
        });
    }
}
