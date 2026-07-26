package com.household.manager.entitystate.mapper;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.tractive.HomeVerdict;
import com.household.manager.tractive.TractiveHomeResolver;
import com.household.manager.tractive.TractivePetSnapshot;
import com.household.manager.tractive.TractiveZoneResolver;
import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mappt einen Tractive-Haustier-Snapshot auf Entity-Zustaende:
 * {@code sensor.tractive_<trackerId>_location} (State = Zonenname oder {@code away}),
 * {@code sensor.tractive_<trackerId>_battery},
 * {@code binary_sensor.tractive_<trackerId>_charging} und
 * {@code binary_sensor.tractive_<trackerId>_home}.
 */
@Component
@RequiredArgsConstructor
public class TractiveEntityMapper {

    /** Suffix der Home-Entitaet; hier definiert, weil diese Klasse alle Entity-IDs baut. */
    private static final String HOME_SUFFIX = "home";

    private final TractiveZoneResolver zoneResolver;
    private final TractiveHomeResolver homeResolver;

    public List<EntityStateUpdate> map(TractivePetSnapshot snapshot) {
        List<EntityStateUpdate> updates = new ArrayList<>();
        String ref = snapshot.trackerId();
        String name = snapshot.name();

        updates.add(locationUpdate(snapshot, ref, name));

        TractiveHardwareDto hardware = snapshot.hardware();
        if (hardware != null && hardware.batteryLevel() != null) {
            updates.add(EntityStateUpdate.builder()
                    .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.TRACTIVE, ref, "battery"))
                    .domain(EntityDomain.SENSOR)
                    .source(EntitySource.TRACTIVE)
                    .sourceRef(ref)
                    .friendlyName(name + " Akku")
                    .state(String.valueOf(hardware.batteryLevel()))
                    .attributes(Map.of("deviceClass", "battery", "unit", "%"))
                    .build());
        }
        if (hardware != null && hardware.chargingState() != null) {
            updates.add(EntityStateUpdate.builder()
                    .entityId(EntityIds.build(EntityDomain.BINARY_SENSOR, EntitySource.TRACTIVE, ref, "charging"))
                    .domain(EntityDomain.BINARY_SENSOR)
                    .source(EntitySource.TRACTIVE)
                    .sourceRef(ref)
                    .friendlyName(name + " laedt")
                    .state(hardware.isCharging() ? "on" : "off")
                    .attributes(Map.of("deviceClass", "battery_charging"))
                    .build());
        }

        // Ohne Urteil wird bewusst kein Update gemeldet: der Entity-State-Layer behaelt
        // dann den letzten Wert, statt einen Zustand zu raten.
        homeResolver.resolve(snapshot, Instant.now())
                .map(verdict -> homeUpdate(verdict, snapshot, ref, name))
                .ifPresent(updates::add);

        return updates;
    }

    /** True fuer die Home-Entitaet dieser Quelle; der Poller nimmt sie davon aus, unavailable zu werden. */
    public boolean isHomeEntity(EntityStateUpdate update) {
        // Muss auch fuer unvollstaendige Updates antworten koennen: Der Poller ruft das im
        // Ausfallpfad auf, und eine Exception wuerde aus der Scheduled-Methode entkommen.
        if (update.source() != EntitySource.TRACTIVE
                || update.sourceRef() == null || update.sourceRef().isBlank()) {
            return false;
        }
        return update.entityId().equals(EntityIds.build(EntityDomain.BINARY_SENSOR,
                EntitySource.TRACTIVE, update.sourceRef(), HOME_SUFFIX));
    }

    private EntityStateUpdate homeUpdate(HomeVerdict verdict, TractivePetSnapshot snapshot,
                                         String ref, String name) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("deviceClass", "presence");
        attributes.put("basis", verdict.basis().name().toLowerCase(Locale.ROOT));
        attributes.put("stale", verdict.stale());
        if (verdict.distanceMeters() != null) {
            attributes.put("distanceMeters", Math.round(verdict.distanceMeters()));
        }
        if (verdict.positionAgeMinutes() != null) {
            attributes.put("positionAgeMinutes", verdict.positionAgeMinutes());
        }
        TractivePositionDto position = snapshot.position();
        if (position != null && position.reportedAt() != null) {
            attributes.put("positionTime", position.reportedAt().toString());
        }
        return EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.BINARY_SENSOR, EntitySource.TRACTIVE, ref, HOME_SUFFIX))
                .domain(EntityDomain.BINARY_SENSOR)
                .source(EntitySource.TRACTIVE)
                .sourceRef(ref)
                .friendlyName(name + " zu Hause")
                .state(verdict.atHome() ? "on" : "off")
                .attributes(attributes)
                .build();
    }

    private EntityStateUpdate locationUpdate(TractivePetSnapshot snapshot, String ref, String name) {
        TractivePositionDto position = snapshot.position();
        Map<String, Object> attributes = new HashMap<>();
        String state = TractiveZoneResolver.UNKNOWN;

        if (position != null && position.hasCoordinates()) {
            state = zoneResolver.resolve(position.latitude(), position.longitude(), snapshot.zones());
            attributes.put("latitude", position.latitude());
            attributes.put("longitude", position.longitude());
            if (position.accuracy() != null) {
                attributes.put("accuracy", position.accuracy());
            }
            if (position.sensorUsed() != null) {
                attributes.put("sensorUsed", position.sensorUsed());
            }
            if (position.reportedAt() != null) {
                attributes.put("positionTime", position.reportedAt().toString());
            }
        }
        return EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.TRACTIVE, ref, "location"))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.TRACTIVE)
                .sourceRef(ref)
                .friendlyName(name)
                .state(state)
                .attributes(attributes)
                .build();
    }
}
