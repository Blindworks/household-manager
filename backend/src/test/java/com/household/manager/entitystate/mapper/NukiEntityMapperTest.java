package com.household.manager.entitystate.mapper;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.nuki.dto.NukiSmartlockDto;
import com.household.manager.nuki.dto.NukiSmartlockStateDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NukiEntityMapperTest {

    private final NukiEntityMapper mapper = new NukiEntityMapper();

    private NukiSmartlockDto smartlock(int state, Integer doorState) {
        return new NukiSmartlockDto(17958143231L, "Haustür",
                new NukiSmartlockStateDto(state, doorState, false, 85));
    }

    @Test
    void mapsLockedStateWithBatteryAttributes() {
        List<EntityStateUpdate> updates = mapper.map(smartlock(1, null));

        assertEquals(1, updates.size());
        EntityStateUpdate lock = updates.get(0);
        assertEquals("lock.nuki_17958143231", lock.entityId());
        assertEquals(EntityDomain.LOCK, lock.domain());
        assertEquals(EntitySource.NUKI, lock.source());
        assertEquals("17958143231", lock.sourceRef());
        assertEquals("Haustür", lock.friendlyName());
        assertEquals("locked", lock.state());
        assertEquals(85, lock.attributes().get("batteryCharge"));
        assertEquals(false, lock.attributes().get("batteryCritical"));
    }

    @Test
    void mapsAllLockStates() {
        assertEquals("uncalibrated", mapper.map(smartlock(0, null)).get(0).state());
        assertEquals("locked", mapper.map(smartlock(1, null)).get(0).state());
        assertEquals("unlocking", mapper.map(smartlock(2, null)).get(0).state());
        assertEquals("unlocked", mapper.map(smartlock(3, null)).get(0).state());
        assertEquals("locking", mapper.map(smartlock(4, null)).get(0).state());
        assertEquals("unlatched", mapper.map(smartlock(5, null)).get(0).state());
        assertEquals("unlocked", mapper.map(smartlock(6, null)).get(0).state());
        assertEquals("unlatching", mapper.map(smartlock(7, null)).get(0).state());
        assertEquals("jammed", mapper.map(smartlock(254, null)).get(0).state());
        assertEquals("unknown", mapper.map(smartlock(255, null)).get(0).state());
    }

    @Test
    void mapsDoorSensorWithOnEqualsOpenSemantics() {
        List<EntityStateUpdate> open = mapper.map(smartlock(1, 3));
        assertEquals(2, open.size());
        EntityStateUpdate door = open.get(1);
        assertEquals("binary_sensor.nuki_17958143231_door", door.entityId());
        assertEquals(EntityDomain.BINARY_SENSOR, door.domain());
        assertEquals("on", door.state());
        assertEquals("Haustür Tür", door.friendlyName());

        assertEquals("off", mapper.map(smartlock(1, 2)).get(1).state());
        assertEquals("unknown", mapper.map(smartlock(1, 4)).get(1).state());
    }

    @Test
    void skipsDoorSensorWhenDeactivatedOrUnavailable() {
        assertEquals(1, mapper.map(smartlock(1, null)).size());
        assertEquals(1, mapper.map(smartlock(1, 0)).size());
        assertEquals(1, mapper.map(smartlock(1, 1)).size());
    }

    @Test
    void survivesMissingStateObject() {
        NukiSmartlockDto broken = new NukiSmartlockDto(1L, "Kaputt", null);
        List<EntityStateUpdate> updates = mapper.map(broken);
        assertEquals(1, updates.size());
        assertEquals("unknown", updates.get(0).state());
    }
}
