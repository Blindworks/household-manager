package com.household.manager.entitystate.mapper;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.SmartDevice;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmartDeviceEntityMapperTest {

    private final SmartDeviceEntityMapper mapper = new SmartDeviceEntityMapper();

    private SmartDevice device(DeviceType type, boolean online, boolean poweredOn) {
        SmartDevice device = new SmartDevice();
        device.setDeviceType(type);
        device.setExternalDeviceId("8006A1B2");
        device.setDeviceName("Wohnzimmer Steckdose");
        device.setModel("HS100");
        device.setIpAddress("192.168.1.50");
        device.setOnline(online);
        device.setPoweredOn(poweredOn);
        return device;
    }

    @Test
    void mapsOnlinePoweredDeviceToSwitchOn() {
        EntityStateUpdate update = mapper.map(device(DeviceType.KASA, true, true));

        assertEquals("switch.kasa_8006a1b2", update.entityId());
        assertEquals(EntityDomain.SWITCH, update.domain());
        assertEquals(EntitySource.KASA, update.source());
        assertEquals("8006A1B2", update.sourceRef());
        assertEquals("Wohnzimmer Steckdose", update.friendlyName());
        assertEquals("on", update.state());
        assertEquals("HS100", update.attributes().get("model"));
        assertEquals("192.168.1.50", update.attributes().get("ipAddress"));
    }

    @Test
    void mapsOnlineUnpoweredDeviceToSwitchOff() {
        EntityStateUpdate update = mapper.map(device(DeviceType.TAPO, true, false));

        assertEquals("switch.tapo_8006a1b2", update.entityId());
        assertEquals("off", update.state());
    }

    @Test
    void mapsOfflineDeviceToUnavailable() {
        EntityStateUpdate update = mapper.map(device(DeviceType.MEROSS, false, true));

        assertEquals(EntitySource.MEROSS, update.source());
        assertEquals("unavailable", update.state());
    }
}
