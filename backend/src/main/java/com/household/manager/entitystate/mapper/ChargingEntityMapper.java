package com.household.manager.entitystate.mapper;

import com.household.manager.charging.ChargePoint;
import com.household.manager.charging.ChargePointStatus;
import com.household.manager.charging.ChargingStationDetails;
import com.household.manager.charging.forecast.ChargingForecast;
import com.household.manager.charging.forecast.ChargingForecastSettings;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.ChargingFavorite;
import com.household.manager.model.entity.ChargingPointOccupancy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Je Favorit eine Entitaet {@code sensor.charging_<stationId>_free} mit State = freie
 * Ladepunkte. Bewusst KEIN deviceClass: die beiden projektweiten Auswertungen ("door" im
 * Modus-Check, "power" im Verbraucher-Service) passen nicht und wuerden falsche Kacheln
 * oder Warnungen erzeugen (siehe BlinkEntityMapper).
 */
@Component
@RequiredArgsConstructor
public class ChargingEntityMapper {

    private static final String SUFFIX = "free";
    private static final DateTimeFormatter ISO_LOCAL_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final Clock clock;

    /** Einzige Definition der Entity-Id; Poller (Markieren) und Mapper fragen dieselbe Stelle. */
    public static String entityId(String stationId) {
        return EntityIds.build(EntityDomain.SENSOR, EntitySource.CHARGING, stationId, SUFFIX);
    }

    public EntityStateUpdate map(ChargingFavorite favorite, ChargingStationDetails details,
                                 Map<String, ChargingPointOccupancy> occupancy,
                                 Optional<ChargingForecast> forecast) {
        long free = details.chargePoints().stream().filter(p -> p.status() == ChargePointStatus.FREE).count();
        Double maxPower = details.chargePoints().stream().map(ChargePoint::maxPowerKw)
                .filter(Objects::nonNull).max(Double::compare).orElse(null);
        Instant oldestSince = occupancy.values().stream().map(ChargingPointOccupancy::getOccupiedSince)
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("total", details.chargePoints().size());
        if (maxPower != null) {
            attributes.put("maxPowerKw", maxPower);
        }
        if (favorite.getDirectPricePerKwh() != null) {
            attributes.put("pricePerKwh", favorite.getDirectPricePerKwh().doubleValue());
            if (favorite.getDirectPriceNote() != null) {
                attributes.put("priceNote", favorite.getDirectPriceNote());
            }
        }
        attributes.put("stationName", favorite.getDisplayName());
        if (favorite.getOperator() != null) {
            attributes.put("operator", favorite.getOperator());
        }
        if (oldestSince != null) {
            attributes.put("occupiedSince", oldestSince.toString());
        }
        forecast.ifPresent(f -> putForecastAttributes(attributes, f));
        return EntityStateUpdate.builder()
                .entityId(entityId(favorite.getStationId()))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.CHARGING)
                .sourceRef(favorite.getStationId())
                .friendlyName(favorite.getDisplayName() + " frei")
                .state(String.valueOf(free))
                .attributes(attributes)
                .build();
    }

    /** Nur bei Status ok tragen die Attribute Werte; sonst steht allein der Status da (nie null). */
    private void putForecastAttributes(Map<String, Object> attributes, ChargingForecast forecast) {
        if (forecast.status() != ChargingForecast.Status.OK) {
            attributes.put("forecastStatus", "insufficient_data");
            return;
        }
        attributes.put("forecastStatus", "ok");
        for (int horizon : ChargingForecastSettings.HORIZONS_MINUTES) {
            Integer percent = forecast.freeProbabilityPercent().get(horizon);
            if (percent != null) {
                attributes.put("freeProbability" + horizon + "min", percent);
            }
        }
        Instant now = clock.instant();
        putTime(attributes, "expectedFreeAt", "minutesUntilFree", forecast.expectedFreeAt(), now);
        putTime(attributes, "expectedFreeLatest", "minutesUntilFreeLatest", forecast.expectedFreeLatest(), now);
    }

    private void putTime(Map<String, Object> attributes, String timeKey, String minutesKey, Instant at, Instant now) {
        if (at == null) {
            return;
        }
        attributes.put(timeKey, ISO_LOCAL_SECONDS.format(LocalDateTime.ofInstant(at, clock.getZone())));
        attributes.put(minutesKey, Math.max(0L, Duration.between(now, at).toMinutes()));
    }
}
