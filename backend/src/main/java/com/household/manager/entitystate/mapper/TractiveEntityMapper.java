package com.household.manager.entitystate.mapper;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.tractive.TractivePetSnapshot;
import com.household.manager.tractive.TractiveZoneResolver;
import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mappt einen Tractive-Haustier-Snapshot auf Entity-Zustaende:
 * {@code sensor.tractive_<trackerId>_location} (State = Zonenname oder {@code away}),
 * {@code sensor.tractive_<trackerId>_battery} und
 * {@code binary_sensor.tractive_<trackerId>_charging}.
 */
@Component
@RequiredArgsConstructor
public class TractiveEntityMapper {

    private final TractiveZoneResolver zoneResolver;

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
        return updates;
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
