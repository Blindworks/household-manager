package com.household.manager.charging;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/** API-Vertrag von /v1/charging. */
public final class ChargingDtos {

    private ChargingDtos() {
    }

    public record HomeResponse(double lat, double lon) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChargePointResponse(String chargePointId, ChargePointStatus status, Double maxPowerKw,
                                      String connector, LocalDateTime occupiedSince, boolean minimumDuration) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StationResponse(String stationId, String name, String operator, String address,
                                  double lat, double lon, long distanceMeters, Double maxPowerKw,
                                  int total, int free, boolean favorite, List<ChargePointResponse> chargePoints) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StationsResponse(boolean configured, HomeResponse home, Double radiusKm, Double minPowerKw,
                                   LocalDateTime lastPolledAt, List<StationResponse> stations) {
    }

    public record SettingsRequest(Double homeLatitude, Double homeLongitude, double radiusKm, double minPowerKw) {
    }

    public record FavoriteResponse(String stationId, String displayName, String operator, double lat, double lon) {
    }
}
