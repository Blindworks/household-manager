package com.household.manager.charging;

import com.household.manager.audit.AuditService;
import com.household.manager.charging.forecast.ChargingForecastService;
import com.household.manager.charging.forecast.ChargingPatternService;
import com.household.manager.entitystate.mapper.ChargingEntityMapper;
import com.household.manager.model.entity.ChargingFavorite;
import com.household.manager.repository.ChargingFavoriteRepository;
import com.household.manager.repository.ChargingObservationRepository;
import com.household.manager.repository.ChargingOccupancyHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Zieht einen Favoriten auf die neue Id seiner Station um, samt Beobachtungszeit und
 * Belegungshistorie, damit die Vorhersage nicht bei null Tagen neu beginnt. Eigene Bean, damit
 * {@link Transactional} ueber den Proxy greift (der Aufrufer {@link ChargingStationMigrator} haelt Zustand).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChargingStationMigrationService {

    private final ChargingFavoriteRepository favorites;
    private final ChargingObservationRepository observations;
    private final ChargingOccupancyHistoryRepository history;
    private final ChargingOccupancyTracker tracker;
    private final ChargingForecastService forecastService;
    private final ChargingPatternService patternService;
    private final AuditService auditService;

    /**
     * Wirft {@link IllegalStateException}, wenn der Umzug nicht eindeutig ist: Favorit weg, Ziel
     * schon Favorit, oder Ziel hat schon eigene Beobachtungszeit (der Unique-Schluessel
     * Station+Stunde wuerde kollidieren, ein Zusammenfuehren raten wir nicht).
     */
    @Transactional
    public void migrate(String oldStationId, ChargingStation successor) {
        String newStationId = successor.stationId();
        ChargingFavorite favorite = favorites.findByStationId(oldStationId).orElseThrow(() ->
                new IllegalStateException("Kein Favorit " + oldStationId));
        if (favorites.existsByStationId(newStationId)) {
            throw new IllegalStateException("Station " + newStationId + " ist bereits Favorit");
        }
        if (observations.existsByStationId(newStationId)) {
            throw new IllegalStateException("Station " + newStationId + " hat bereits eigene Beobachtungszeit");
        }
        favorite.setStationId(newStationId);
        favorite.setLat(successor.lat());
        favorite.setLon(successor.lon());
        favorites.save(favorite);
        int hours = observations.reassignStation(oldStationId, newStationId);
        int sessions = history.reassignStation(oldStationId, newStationId);
        // Laufende Belegungen gehoeren zu den alten Ladepunkt-Ids; der Tracker baut sie unter der neuen Id neu auf
        tracker.forgetStation(oldStationId);
        forecastService.forget(oldStationId);
        patternService.evict(oldStationId);
        patternService.evict(newStationId);
        String detail = oldStationId + " -> " + newStationId + " (" + favorite.getDisplayName() + ", "
                + hours + " Beobachtungsstunden, " + sessions + " Belegungen)";
        auditService.record("charging.favorite.migrate", detail);
        log.warn("Ladesaeulen-Favorit umgezogen: {}. Die Entitaet heisst jetzt {} - Flows auf {} laufen ins Leere.",
                detail, ChargingEntityMapper.entityId(newStationId),
                ChargingEntityMapper.entityId(oldStationId));
    }
}
