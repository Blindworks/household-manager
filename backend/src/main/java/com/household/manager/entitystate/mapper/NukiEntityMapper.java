package com.household.manager.entitystate.mapper;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.nuki.NukiLockStates;
import com.household.manager.nuki.dto.NukiSmartlockDto;
import com.household.manager.nuki.dto.NukiSmartlockStateDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mappt ein Nuki-Smartlock auf Entity-Zustände: das Schloss als
 * {@code lock.nuki_<smartlockId>}, der optionale Türsensor als
 * {@code binary_sensor.nuki_<smartlockId>_door} (on = offen).
 */
@Component
public class NukiEntityMapper {

    public List<EntityStateUpdate> map(NukiSmartlockDto smartlock) {
        List<EntityStateUpdate> updates = new ArrayList<>();
        String ref = String.valueOf(smartlock.smartlockId());
        NukiSmartlockStateDto state = smartlock.state();

        Map<String, Object> attributes = new HashMap<>();
        if (state != null && state.batteryCharge() != null) {
            attributes.put("batteryCharge", state.batteryCharge());
        }
        if (state != null && state.batteryCritical() != null) {
            attributes.put("batteryCritical", state.batteryCritical());
        }

        updates.add(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.LOCK, EntitySource.NUKI, ref, null))
                .domain(EntityDomain.LOCK)
                .source(EntitySource.NUKI)
                .sourceRef(ref)
                .friendlyName(smartlock.name())
                .state(NukiLockStates.lockState(state != null ? state.state() : null))
                .attributes(attributes)
                .build());

        String doorState = NukiLockStates.doorState(state != null ? state.doorState() : null);
        if (doorState != null) {
            updates.add(EntityStateUpdate.builder()
                    .entityId(EntityIds.build(EntityDomain.BINARY_SENSOR, EntitySource.NUKI, ref, "door"))
                    .domain(EntityDomain.BINARY_SENSOR)
                    .source(EntitySource.NUKI)
                    .sourceRef(ref)
                    .friendlyName(smartlock.name() + " Tür")
                    .state(doorState)
                    .attributes(Map.of("deviceClass", "door"))
                    .build());
        }
        return updates;
    }
}
