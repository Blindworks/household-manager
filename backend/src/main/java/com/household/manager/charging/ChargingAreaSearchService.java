package com.household.manager.charging;

import com.household.manager.charging.supercharger.SuperchargerCatalog;
import com.household.manager.model.entity.ChargingFavorite;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * „In diesem Bereich suchen“ auf der Tablet-Karte: einmalige Suche im sichtbaren Kartenausschnitt
 * (kein Kreis ums Zuhause). Nutzt denselben Drill-down der Quelle wie der Umkreis-Poll und haelt
 * den Ausschnitt klein, weil jede Suche bis zu 150 Quellen-Abrufe kosten kann.
 */
@Service
@RequiredArgsConstructor
public class ChargingAreaSearchService {

    /** Gegenstueck zu SEARCH_MAX_DIAGONAL_KM im Frontend (nur Bedienhilfe dort, verbindlich hier). */
    public static final double MAX_SEARCH_DIAGONAL_KM = 30;
    static final Duration MIN_SEARCH_GAP = Duration.ofSeconds(10);

    private final ChargingProperties properties;
    private final ChargingStationSource source;
    private final ChargingSettingsService settingsService;
    private final ChargingFavoriteService favoriteService;
    private final SuperchargerCatalog superchargerCatalog;
    private final ChargingSearchResults searchResults;
    private final Clock clock;

    private final Object gapLock = new Object();
    private Instant lastSearchAt;

    /** 400 bei ungueltigem/zu grossem Ausschnitt oder fehlendem Zuhause, 429 bei der Sperre, 502 bei Quellenfehler. */
    public ChargingDtos.SearchResponse search(BoundingBox box) {
        validate(box);
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Die Ladesaeulen-Anbindung ist deaktiviert (charging.enabled=false).");
        }
        ChargingSettings settings = settingsService.getSettings();
        if (!settings.isConfigured()) {
            throw new IllegalStateException("Es ist kein Zuhause fuer die Ladesaeulen-Uebersicht konfiguriert.");
        }
        Instant now = reserveSearchSlot();

        List<ChargingStation> found = ChargingAreaFilter.filterInBox(
                source.searchArea(box, settings.minPowerKw()), box, settings.minPowerKw());
        searchResults.remember(found);

        double homeLat = settings.homeLatitude();
        double homeLon = settings.homeLongitude();
        Set<String> favoriteIds = favoriteService.list().stream()
                .map(ChargingFavorite::getStationId).collect(Collectors.toSet());
        List<ChargingDtos.StationResponse> rows = Stream.concat(
                        found.stream().map(s -> ChargingStationRows.area(s, homeLat, homeLon,
                                favoriteIds.contains(s.stationId()))),
                        ChargingAreaFilter.filterSitesInBox(superchargerCatalog.sites(), box,
                                        settings.minPowerKw()).stream()
                                .map(s -> ChargingStationRows.supercharger(s, homeLat, homeLon)))
                .sorted(Comparator.comparingLong(ChargingDtos.StationResponse::distanceMeters))
                .toList();
        return new ChargingDtos.SearchResponse(LocalDateTime.ofInstant(now, clock.getZone()), rows);
    }

    /** Reserviert den Mindestabstand VOR dem Abruf (sonst messen Doppelklick und zwei Tabs doppelt). */
    private Instant reserveSearchSlot() {
        synchronized (gapLock) {
            Instant now = clock.instant();
            if (lastSearchAt != null && now.isBefore(lastSearchAt.plus(MIN_SEARCH_GAP))) {
                throw new ChargingRateLimitException("Die letzte Suche war gerade eben. Bitte "
                        + MIN_SEARCH_GAP.toSeconds() + " Sekunden warten.");
            }
            lastSearchAt = now;
            return now;
        }
    }

    private static void validate(BoundingBox box) {
        double[] values = {box.fromLat(), box.toLat(), box.fromLon(), box.toLon()};
        for (double value : values) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Der Kartenausschnitt enthaelt ungueltige Werte.");
            }
        }
        if (box.fromLat() < -90 || box.toLat() > 90 || box.fromLon() < -180 || box.toLon() > 180) {
            throw new IllegalArgumentException("Der Kartenausschnitt liegt ausserhalb des gueltigen Bereichs.");
        }
        if (box.fromLat() >= box.toLat() || box.fromLon() >= box.toLon()) {
            throw new IllegalArgumentException("Der Kartenausschnitt ist leer: von muss kleiner als bis sein.");
        }
        double diagonalKm = ChargingAreaFilter.distanceMeters(box.fromLat(), box.fromLon(), box.toLat(),
                box.toLon()) / 1000;
        if (diagonalKm > MAX_SEARCH_DIAGONAL_KM) {
            throw new IllegalArgumentException("Ausschnitt zu groß – bitte näher heranzoomen");
        }
    }
}
