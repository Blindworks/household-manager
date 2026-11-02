package com.household.manager.charging;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** API-Vertrag von /v1/charging. */
public final class ChargingDtos {

    private ChargingDtos() {
    }

    public record HomeResponse(double lat, double lon) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChargePointResponse(String chargePointId, ChargePointStatus status, Double maxPowerKw,
                                      String connector, LocalDateTime occupiedSince, boolean minimumDuration,
                                      Double pricePerKwh) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StationResponse(String stationId, String name, String operator, String address,
                                  double lat, double lon, long distanceMeters, Double maxPowerKw,
                                  Double pricePerKwh, int total, int free, boolean favorite,
                                  List<ChargePointResponse> chargePoints, ForecastSummary forecast) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StationsResponse(boolean configured, HomeResponse home, Double radiusKm, Double minPowerKw,
                                   LocalDateTime lastPolledAt, List<StationResponse> stations) {
    }

    public record SettingsRequest(Double homeLatitude, Double homeLongitude, double radiusKm, double minPowerKw) {
    }

    public record FavoriteResponse(String stationId, String displayName, String operator, double lat, double lon) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ForecastSummary(String status, Integer freeProbability30min, LocalDateTime expectedFreeAt,
                                  LocalDateTime expectedFreeLatest, Long observedDays) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PointForecastResponse(String chargePointId, LocalDateTime occupiedSince,
                                        LocalDateTime expectedFreeAt, LocalDateTime expectedFreeLatest) {
    }

    /** Heatmap: 7 Wochentage (Mo=0) x 24 Stunden, {@code null} = keine Daten. */
    public record ForecastResponse(String stationId, String status, long observedDays,
                                   Map<Integer, Integer> freeProbabilityPercent,
                                   List<PointForecastResponse> points, Double[][] heatmap) {
    }
}
