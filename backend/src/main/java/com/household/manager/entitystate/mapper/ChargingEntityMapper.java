package com.household.manager.entitystate.mapper;

import com.household.manager.charging.ChargePoint;
import com.household.manager.charging.ChargePointStatus;
import com.household.manager.charging.ChargingStationDetails;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.ChargingFavorite;
import com.household.manager.model.entity.ChargingPointOccupancy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Je Favorit eine Entitaet {@code sensor.charging_<stationId>_free} mit State = freie
 * Ladepunkte. Bewusst KEIN deviceClass: die beiden projektweiten Auswertungen ("door" im
 * Modus-Check, "power" im Verbraucher-Service) passen nicht und wuerden falsche Kacheln
 * oder Warnungen erzeugen (siehe BlinkEntityMapper).
 */
@Component
public class ChargingEntityMapper {

    private static final String SUFFIX = "free";

    /** Einzige Definition der Entity-Id; Poller (Markieren) und Mapper fragen dieselbe Stelle. */
    public static String entityId(String stationId) {
        return EntityIds.build(EntityDomain.SENSOR, EntitySource.CHARGING, stationId, SUFFIX);
    }

    public EntityStateUpdate map(ChargingFavorite favorite, ChargingStationDetails details,
                                 Map<String, ChargingPointOccupancy> occupancy) {
        long free = details.chargePoints().stream().filter(p -> p.status() == ChargePointStatus.FREE).count();
        Double maxPower = details.chargePoints().stream().map(ChargePoint::maxPowerKw)
                .filter(Objects::nonNull).max(Double::compare).orElse(null);
        Double minPrice = details.chargePoints().stream().map(ChargePoint::pricePerKwh)
                .filter(Objects::nonNull).min(Double::compare).orElse(null);
        Instant oldestSince = occupancy.values().stream().map(ChargingPointOccupancy::getOccupiedSince)
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("total", details.chargePoints().size());
        if (maxPower != null) {
            attributes.put("maxPowerKw", maxPower);
        }
        if (minPrice != null) {
            attributes.put("pricePerKwh", minPrice);
        }
        attributes.put("stationName", favorite.getDisplayName());
        if (favorite.getOperator() != null) {
            attributes.put("operator", favorite.getOperator());
        }
        if (oldestSince != null) {
            attributes.put("occupiedSince", oldestSince.toString());
        }
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
}
