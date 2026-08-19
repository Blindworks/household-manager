package com.household.manager.entitystate.mapper;

import com.household.manager.entitystate.*;
import com.household.manager.model.entity.SmartDevice;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Übersetzt ein {@link SmartDevice} (Kasa/Tapo/Meross) in eine Switch-Entität.
 */
@Component
public class SmartDeviceEntityMapper {

    public EntityStateUpdate map(SmartDevice device) {
        // DeviceType-Namen (KASA/TAPO/MEROSS) stimmen mit EntitySource überein
        EntitySource source = EntitySource.valueOf(device.getDeviceType().name());

        String state;
        if (!device.isOnline()) {
            state = "unavailable";
        } else {
            state = device.isPoweredOn() ? "on" : "off";
        }

        Map<String, Object> attributes = new HashMap<>();
        if (device.getModel() != null) {
            attributes.put("model", device.getModel());
        }
        if (device.getIpAddress() != null) {
            attributes.put("ipAddress", device.getIpAddress());
        }

        return EntityStateUpdate.builder()
                .entityId(entityId(device))
                .domain(EntityDomain.SWITCH)
                .source(source)
                .sourceRef(device.getExternalDeviceId())
                .friendlyName(device.getDeviceName())
                .state(state)
                .attributes(attributes)
                .build();
    }

    /** Einzige Definition der Switch-entityId eines SmartDevice. */
    public String entityId(SmartDevice device) {
        return EntityIds.build(EntityDomain.SWITCH,
                EntitySource.valueOf(device.getDeviceType().name()),
                device.getExternalDeviceId(), null);
    }
}
