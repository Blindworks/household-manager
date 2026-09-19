package com.household.manager.charging;

import com.household.manager.model.entity.ChargingFavorite;
import com.household.manager.model.entity.ChargingPointOccupancy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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

/**
 * Baut die eine Antwort fuer Tablet und Website: Favoriten zuerst (mit Ladepunkten und
 * Belegungsbeginn), dann die uebrigen Umkreis-Stationen nach Entfernung.
 */
@Service
@RequiredArgsConstructor
public class ChargingQueryService {

    private final ChargingSettingsService settingsService;
    private final ChargingFavoriteService favoriteService;
    private final ChargingOccupancyTracker tracker;
    private final ChargingSnapshot snapshot;
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

        List<ChargingDtos.StationResponse> others = snapshot.areaStations().stream()
                .filter(s -> !favorites.containsKey(s.stationId()))
                .map(s -> areaRow(s, homeLat, homeLon))
                .sorted(Comparator.comparingLong(ChargingDtos.StationResponse::distanceMeters))
                .toList();

        List<ChargingDtos.StationResponse> all = new ArrayList<>(favoriteRows);
        all.addAll(others);
        return new ChargingDtos.StationsResponse(true, new ChargingDtos.HomeResponse(homeLat, homeLon),
                settings.radiusKm(), settings.minPowerKw(), local(snapshot.lastPolledAt()), all);
    }

    private ChargingDtos.StationResponse areaRow(ChargingStation s, double homeLat, double homeLon) {
        long distance = Math.round(ChargingAreaFilter.distanceMeters(homeLat, homeLon, s.lat(), s.lon()));
        return new ChargingDtos.StationResponse(s.stationId(), s.name(), s.operator(), s.address(), s.lat(), s.lon(),
                distance, s.maxPowerKw(), s.total(), s.free(), false, null);
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

        return new ChargingDtos.StationResponse(favorite.getStationId(), name, operator, address, lat, lon,
                distance, maxPower, total, free, true, points);
    }

    /**
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
                point.connector(), since, minimum);
    }

    private LocalDateTime local(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, clock.getZone());
    }
}
