package com.household.manager.charging;

import com.household.manager.model.entity.ChargingFavorite;
import com.household.manager.model.entity.ChargingPointOccupancy;
import com.household.manager.charging.forecast.ChargingForecast;
import com.household.manager.charging.forecast.ChargingForecastService;
import com.household.manager.charging.supercharger.SuperchargerCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Baut die eine Antwort fuer Tablet und Website: Favoriten zuerst (mit Ladepunkten und
 * Belegungsbeginn), dann die uebrigen Umkreis-Stationen nach Entfernung.
 */
@Service
@RequiredArgsConstructor
public class ChargingQueryService {

    /** Praefix der Supercharger-Ids; auch der Controller lehnt damit Favorit/Ladepunkte/Vorhersage ab. */
    public static final String SUPERCHARGER_ID_PREFIX = "sc-";
    private static final String INSUFFICIENT_DATA = "INSUFFICIENT_DATA";

    private final ChargingSettingsService settingsService;
    private final ChargingFavoriteService favoriteService;
    private final ChargingOccupancyTracker tracker;
    private final ChargingSnapshot snapshot;
    private final ChargingForecastService forecastService;
    private final SuperchargerCatalog superchargerCatalog;
    private final Clock clock;

    public ChargingDtos.StationsResponse stations() {
        ChargingSettings settings = settingsService.getSettings();
        if (!settings.isConfigured()) {
            return new ChargingDtos.StationsResponse(false, null, settings.radiusKm(), settings.minPowerKw(),
                    null, List.of());
        }
        double homeLat = settings.homeLatitude();
        double homeLon = settings.homeLongitude();

        Map<String, ChargingFavorite> favorites = new LinkedHashMap<>();
        favoriteService.list().forEach(f -> favorites.put(f.getStationId(), f));

        List<ChargingDtos.StationResponse> favoriteRows = new ArrayList<>();
        for (ChargingFavorite favorite : favorites.values()) {
            favoriteRows.add(favoriteRow(favorite, homeLat, homeLon));
        }
        favoriteRows.sort(Comparator.comparingLong(ChargingDtos.StationResponse::distanceMeters));

        List<ChargingDtos.StationResponse> others = Stream.concat(
                        snapshot.areaStations().stream()
                                .filter(s -> !favorites.containsKey(s.stationId()))
                                .map(s -> ChargingStationRows.area(s, homeLat, homeLon, false)),
                        ChargingAreaFilter.filterSites(superchargerCatalog.sites(), homeLat, homeLon,
                                        settings.radiusKm(), settings.minPowerKw()).stream()
                                .map(s -> ChargingStationRows.supercharger(s, homeLat, homeLon)))
                .sorted(Comparator.comparingLong(ChargingDtos.StationResponse::distanceMeters))
                .toList();

        List<ChargingDtos.StationResponse> all = new ArrayList<>(favoriteRows);
        all.addAll(others);
        return new ChargingDtos.StationsResponse(true, new ChargingDtos.HomeResponse(homeLat, homeLon),
                settings.radiusKm(), settings.minPowerKw(), local(snapshot.lastPolledAt()), all);
    }

    /** Stammdaten aus dem Umkreis-Stand, wenn vorhanden; sonst die beim Favorisieren gespeicherten. */
    private ChargingDtos.StationResponse favoriteRow(ChargingFavorite favorite, double homeLat, double homeLon) {
        Optional<ChargingStation> area = snapshot.station(favorite.getStationId());
        Optional<ChargingStationDetails> details = snapshot.details(favorite.getStationId());
        Map<String, ChargingPointOccupancy> occupancy = details.isPresent()
                ? tracker.occupancyFor(favorite.getStationId()) : Map.of();

        double lat = area.map(ChargingStation::lat).orElse(favorite.getLat());
        double lon = area.map(ChargingStation::lon).orElse(favorite.getLon());
        String name = area.map(ChargingStation::name).orElse(favorite.getDisplayName());
        String operator = area.map(ChargingStation::operator).orElse(favorite.getOperator());
        String address = area.map(ChargingStation::address).orElse(null);
        long distance = Math.round(ChargingAreaFilter.distanceMeters(homeLat, homeLon, lat, lon));

        List<ChargingDtos.ChargePointResponse> points = details.map(d -> d.chargePoints().stream()
                .map(p -> pointRow(p, occupancy.get(p.chargePointId()))).toList()).orElse(null);
        int total = details.map(d -> d.chargePoints().size()).orElse(area.map(ChargingStation::total).orElse(0));
        int free = details.map(d -> (int) d.chargePoints().stream()
                .filter(p -> p.status() == ChargePointStatus.FREE).count())
                .orElse(area.map(ChargingStation::free).orElse(0));
        Double maxPower = details.map(d -> d.chargePoints().stream().map(ChargePoint::maxPowerKw)
                .filter(Objects::nonNull).max(Double::compare).orElse(null))
                .orElse(area.map(ChargingStation::maxPowerKw).orElse(null));
        Double directPrice = favorite.getDirectPricePerKwh() == null ? null
                : favorite.getDirectPricePerKwh().doubleValue();

        return new ChargingDtos.StationResponse(favorite.getStationId(), name, operator, address, lat, lon,
                distance, maxPower, directPrice, favorite.getDirectPriceNote(), total, free, true, points,
                summary(forecastService.latest(favorite.getStationId())), null, null);
    }

    /** Vorhersage einer Favoritenstation; Nicht-Favoriten haben keine (404). */
    public ChargingDtos.ForecastResponse forecast(String stationId) {
        boolean favorite = favoriteService.list().stream().anyMatch(f -> stationId.equals(f.getStationId()));
        if (!favorite) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Keine Favoritenstation: " + stationId);
        }
        return forecastService.latest(stationId).map(f -> toResponse(stationId, f))
                .orElseGet(() -> new ChargingDtos.ForecastResponse(stationId, INSUFFICIENT_DATA, 0, Map.of(),
                        List.of(), new Double[7][24]));
    }

    private ChargingDtos.ForecastResponse toResponse(String stationId, ChargingForecast f) {
        List<ChargingDtos.PointForecastResponse> points = f.points().stream()
                .map(p -> new ChargingDtos.PointForecastResponse(p.chargePointId(), local(p.occupiedSince()),
                        local(p.expectedFreeAt()), local(p.expectedFreeLatest())))
                .toList();
        return new ChargingDtos.ForecastResponse(stationId, f.status().name(), f.observedDays(),
                f.freeProbabilityPercent(), points, f.heatmap());
    }

    private ChargingDtos.ForecastSummary summary(Optional<ChargingForecast> forecast) {
        return forecast
                .map(f -> new ChargingDtos.ForecastSummary(f.status().name(),
                        f.freeProbabilityPercent().get(30), local(f.expectedFreeAt()),
                        local(f.expectedFreeLatest()), f.observedDays()))
                .orElseGet(() -> new ChargingDtos.ForecastSummary(INSUFFICIENT_DATA, null, null, null, 0L));
    }

    /**
     * Favoriten-Ladepunkte tragen nie einen Preis: der EnBW-Preis ist der Roaming-Tarif und trifft
     * nicht, was direkt beim Betreiber bezahlt wird (Preis steht nur als Direktpreis an der Station).
     * Beginn bekannt -> occupiedSince, minimumDuration=false. Beginn unbekannt (beim ersten Poll
     * schon belegt) -> firstSeenOccupiedAt als Untergrenze, minimumDuration=true.
     */
    private ChargingDtos.ChargePointResponse pointRow(ChargePoint point, ChargingPointOccupancy occupancy) {
        LocalDateTime since = null;
        boolean minimum = false;
        if (occupancy != null && point.status() == ChargePointStatus.OCCUPIED) {
            if (occupancy.getOccupiedSince() != null) {
                since = local(occupancy.getOccupiedSince());
            } else {
                since = local(occupancy.getFirstSeenOccupiedAt());
                minimum = true;
            }
        }
        return new ChargingDtos.ChargePointResponse(point.chargePointId(), point.status(), point.maxPowerKw(),
                point.connector(), since, minimum, null);
    }

    private LocalDateTime local(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, clock.getZone());
    }
}
