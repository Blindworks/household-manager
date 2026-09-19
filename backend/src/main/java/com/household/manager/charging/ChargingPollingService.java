package com.household.manager.charging;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.ChargingEntityMapper;
import com.household.manager.model.entity.ChargingFavorite;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zwei getrennte Poll-Pfade (Umkreis alle 5 min, Favoriten jede Minute), beide werfen nie.
 * Ein Fehler bei einem Favoriten stoert die anderen nicht; bei einem Rate-Limit bricht der
 * Durchlauf sofort ab (jeder weitere Abruf wuerde es hochschaukeln).
 */
@Service
@Slf4j
public class ChargingPollingService {

    private static final Duration MIN_FORCED_REFRESH_GAP = Duration.ofSeconds(15);

    private final ChargingProperties properties;
    private final ChargingStationSource source;
    private final ChargingSettingsService settingsService;
    private final ChargingFavoriteService favoriteService;
    private final ChargingOccupancyTracker tracker;
    private final ChargingSnapshot snapshot;
    private final ChargingEntityMapper mapper;
    private final EntityStateService entityStateService;
    private final Clock clock;

    /** Zuletzt erfolgreich gemeldete Updates je Station; Basis fuer unavailable mit erhaltenen Attributen. */
    private final Map<String, EntityStateUpdate> lastReported = new HashMap<>();
    private volatile Instant lastForcedRefreshAt;

    public ChargingPollingService(ChargingProperties properties, ChargingStationSource source,
                                  ChargingSettingsService settingsService, ChargingFavoriteService favoriteService,
                                  ChargingOccupancyTracker tracker, ChargingSnapshot snapshot,
                                  ChargingEntityMapper mapper, EntityStateService entityStateService, Clock clock) {
        this.properties = properties;
        this.source = source;
        this.settingsService = settingsService;
        this.favoriteService = favoriteService;
        this.tracker = tracker;
        this.snapshot = snapshot;
        this.mapper = mapper;
        this.entityStateService = entityStateService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "#{${charging.area-poll-seconds:300} * 1000}",
            initialDelayString = "${charging.initial-delay-ms:25000}")
    public void scheduledArea() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            pollArea();
        } catch (Exception ex) {
            log.warn("Ladesaeulen-Umkreis-Poll fehlgeschlagen: {}", ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "#{${charging.favorite-poll-seconds:60} * 1000}",
            initialDelayString = "${charging.initial-delay-ms:25000}")
    public void scheduledFavorites() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            pollFavorites();
        } catch (Exception ex) {
            log.warn("Ladesaeulen-Favoriten-Poll fehlgeschlagen: {}", ex.getMessage());
        }
    }

    /** Ein Umkreis-Abruf; wirft bei Quellenfehler (der Scheduler faengt, refreshNow reicht durch). */
    public void pollArea() {
        ChargingSettings settings = settingsService.getSettings();
        if (!settings.isConfigured()) {
            log.debug("Ladesaeulen: kein Zuhause konfiguriert, kein Umkreis-Poll");
            return;
        }
        BoundingBox box = BoundingBox.around(settings.homeLatitude(), settings.homeLongitude(), settings.radiusKm());
        List<ChargingStation> raw = source.searchArea(box, settings.minPowerKw());
        List<ChargingStation> filtered = ChargingAreaFilter.filter(raw, settings.homeLatitude(),
                settings.homeLongitude(), settings.radiusKm(), settings.minPowerKw());
        snapshot.updateArea(filtered, clock.instant());
        log.debug("Ladesaeulen-Umkreis: {} von {} Stationen im Kreis", filtered.size(), raw.size());
    }

    /** Ein Favoriten-Durchlauf; Fehler je Favorit isoliert, Rate-Limit bricht ab. */
    public synchronized void pollFavorites() {
        List<ChargingFavorite> favorites = favoriteService.list();
        Map<String, ChargingStationDetails> details = new LinkedHashMap<>();
        Map<String, ChargingFavorite> current = new LinkedHashMap<>();
        favorites.forEach(f -> current.put(f.getStationId(), f));

        // Entfernte Favoriten: einmal unavailable, dann vergessen (sonst blieben sie ewig auf dem letzten Wert).
        for (String stationId : List.copyOf(lastReported.keySet())) {
            if (!current.containsKey(stationId)) {
                reportUnavailable(stationId);
                lastReported.remove(stationId);
            }
        }

        for (ChargingFavorite favorite : favorites) {
            String stationId = favorite.getStationId();
            try {
                ChargingStationDetails stationDetails = source.stationDetails(stationId);
                tracker.record(stationDetails);
                details.put(stationId, stationDetails);
                EntityStateUpdate update = mapper.map(favorite, stationDetails, tracker.occupancyFor(stationId));
                entityStateService.reportState(update);
                lastReported.put(stationId, update);
            } catch (ChargingRateLimitException ex) {
                log.warn("Ladesaeulen-Favoriten: Rate-Limit bei {}, Durchlauf abgebrochen", stationId);
                reportFetchFailureUnavailable(favorite);
                break;
            } catch (Exception ex) {
                log.warn("Ladesaeulen-Favorit {} nicht lesbar: {}", stationId, ex.getMessage());
                reportFetchFailureUnavailable(favorite);
            }
        }
        // Erfolgreich gelesene Details ersetzen den Stand; fehlgeschlagene behalten den alten Snapshot-Eintrag.
        Map<String, ChargingStationDetails> merged = new LinkedHashMap<>();
        for (String stationId : current.keySet()) {
            ChargingStationDetails fresh = details.get(stationId);
            if (fresh != null) {
                merged.put(stationId, fresh);
            } else {
                snapshot.details(stationId).ifPresent(old -> merged.put(stationId, old));
            }
        }
        Instant polledAt = (!details.isEmpty() || favorites.isEmpty()) ? clock.instant() : snapshot.lastPolledAt();
        snapshot.updateFavoriteDetails(merged, polledAt);
    }

    /**
     * Erzwungener Abruf (Knopf "Jetzt aktualisieren"). Reicht Fehler durch: 400 bei fehlendem
     * Zuhause (IllegalStateException), 429 bei Mindestabstand, 502 bei Quellenfehler.
     */
    public void refreshNow() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Die Ladesaeulen-Anbindung ist deaktiviert (charging.enabled=false).");
        }
        if (!settingsService.getSettings().isConfigured()) {
            throw new IllegalStateException("Es ist kein Zuhause fuer die Ladesaeulen-Uebersicht konfiguriert.");
        }
        Instant now = clock.instant();
        Instant last = lastForcedRefreshAt;
        if (last != null && now.isBefore(last.plus(MIN_FORCED_REFRESH_GAP))) {
            throw new ChargingRateLimitException("Der letzte Abruf war gerade eben. Bitte "
                    + MIN_FORCED_REFRESH_GAP.toSeconds() + " Sekunden warten.");
        }
        lastForcedRefreshAt = now;
        pollArea();
        pollFavorites();
    }

    /**
     * Ein Favorit, dessen Detailabruf gerade fehlschlug: die Station ist noch bekannt (wir
     * haben das {@link ChargingFavorite}-Objekt), also wird unavailable direkt daraus gebaut,
     * nicht ueber {@link #lastReported} gegated - sonst bekaeme ein Favorit, dessen allererster
     * Poll scheitert, nie eine Meldung.
     */
    private void reportFetchFailureUnavailable(ChargingFavorite favorite) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("stationName", favorite.getDisplayName());
        if (favorite.getOperator() != null) {
            attributes.put("operator", favorite.getOperator());
        }
        EntityStateUpdate update = EntityStateUpdate.builder()
                .entityId(ChargingEntityMapper.entityId(favorite.getStationId()))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.CHARGING)
                .sourceRef(favorite.getStationId())
                .friendlyName(favorite.getDisplayName() + " frei")
                .state("unavailable")
                .attributes(attributes)
                .build();
        entityStateService.reportState(update);
        lastReported.put(favorite.getStationId(), update);
    }

    /** unavailable MIT erhaltenen Attributen (EntityStateWriter.upsert ueberschreibt sie sonst komplett). */
    private void reportUnavailable(String stationId) {
        EntityStateUpdate previous = lastReported.get(stationId);
        if (previous == null) {
            return;
        }
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(previous.entityId())
                .domain(previous.domain())
                .source(previous.source())
                .sourceRef(previous.sourceRef())
                .friendlyName(previous.friendlyName())
                .state("unavailable")
                .attributes(previous.attributes())
                .build());
    }
}
