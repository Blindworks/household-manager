package com.household.manager.thermostat;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.Thermostat;
import com.household.manager.tapo.hub.ThermostatMode;
import com.household.manager.tapo.hub.ThermostatReading;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ThermostatEntityMapperTest {

    private final ThermostatEntityMapper mapper = new ThermostatEntityMapper();
    private final Thermostat thermostat = Thermostat.builder()
            .id(3L).childDeviceId("KE-3").hubDeviceId("HUB").hubIp("1.2.3.4").name("Wohnzimmer").active(true).build();

    private EntityStateUpdate byId(List<EntityStateUpdate> updates, String entityId) {
        return updates.stream().filter(u -> u.entityId().equals(entityId)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Entity-Ids haengen an der DB-Id, nicht am Namen")
    void entityIdsUseDatabaseId() {
        assertEquals("sensor.thermostat_3_temperature", ThermostatEntityMapper.entityId(thermostat, "temperature"));
        assertEquals("sensor.thermostat_3_target", ThermostatEntityMapper.entityId(thermostat, "target"));
        assertEquals("binary_sensor.thermostat_3_heating", ThermostatEntityMapper.entityId(thermostat, "heating"));
    }

    @Test
    @DisplayName("Online-Zustand ergibt drei Entitaeten mit Zahlen- und on/off-Strings")
    void onlineReadingMapsToThreeEntities() {
        ThermostatReading reading = new ThermostatReading("KE-3", "x", true, 20.5, 21.0,
                ThermostatMode.MANUAL, true, false);

        List<EntityStateUpdate> updates = mapper.map(thermostat, reading, id -> Map.of());

        assertEquals(3, updates.size());
        assertEquals("20.5", byId(updates, "sensor.thermostat_3_temperature").state());
        assertEquals("21", byId(updates, "sensor.thermostat_3_target").state());
        EntityStateUpdate heating = byId(updates, "binary_sensor.thermostat_3_heating");
        assertEquals("on", heating.state());
        assertEquals(EntityDomain.BINARY_SENSOR, heating.domain());
        assertEquals(EntitySource.THERMOSTAT, heating.source());
        assertEquals("3", heating.sourceRef());
    }

    @Test
    @DisplayName("Kein deviceClass an keiner der Entitaeten")
    void noDeviceClassOnAnyEntity() {
        ThermostatReading reading = new ThermostatReading("KE-3", "x", true, 20.5, 21.0,
                ThermostatMode.MANUAL, false, false);

        mapper.map(thermostat, reading, id -> Map.of()).forEach(update ->
                assertFalse(update.attributes().containsKey("deviceClass"), update.entityId()));
        mapper.unavailable(thermostat, id -> Map.of("unit", "°C")).forEach(update ->
                assertFalse(update.attributes().containsKey("deviceClass"), update.entityId()));
    }

    @Test
    @DisplayName("Offline-Geraet: alle drei unavailable, Attribute des letzten Standes bleiben erhalten")
    void offlineReadingIsUnavailableWithPreservedAttributes() {
        ThermostatReading offline = new ThermostatReading("KE-3", "x", false, null, null,
                ThermostatMode.MANUAL, false, false);

        List<EntityStateUpdate> updates = mapper.map(thermostat, offline,
                id -> Map.of("unit", "°C", "mode", "MANUAL"));

        assertEquals(3, updates.size());
        updates.forEach(update -> {
            assertEquals("unavailable", update.state());
            assertEquals("°C", update.attributes().get("unit"));
            assertEquals("MANUAL", update.attributes().get("mode"));
        });
    }

    @Test
    @DisplayName("Online ohne Temperatur: Temperatur unknown statt eines geratenen Wertes")
    void onlineWithoutValueIsUnknown() {
        ThermostatReading reading = new ThermostatReading("KE-3", "x", true, null, null,
                ThermostatMode.MANUAL, false, false);

        List<EntityStateUpdate> updates = mapper.map(thermostat, reading, id -> Map.of());

        assertEquals("unknown", byId(updates, "sensor.thermostat_3_temperature").state());
        assertEquals("unknown", byId(updates, "sensor.thermostat_3_target").state());
    }
}
